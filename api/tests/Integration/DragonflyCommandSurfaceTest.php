<?php

declare(strict_types=1);

namespace Tests\Integration;

use PHPUnit\Framework\TestCase;
use Redis;
use RuntimeException;
use Tests\Support\RedisCommandRecorder;

/**
 * Dragonfly command-surface test — plan §13.5, §20 P0.6.4.
 *
 * Replays the ENTIRE pinned command list (api/tests/Support/
 * dragonfly_command_surface.php) against the live pinned Dragonfly container
 * and fails on any error reply, so the verified surface cannot silently grow
 * past what was proven against this image tag.
 *
 * The list's provenance (plan §13.5 relied-on surface + the commands
 * webman/redis-queue and the app itself actually issue) is documented in the
 * surface file's meta block.
 *
 * Fence probes (§13.5): Redis Functions (FUNCTION LOAD/FCALL) and CLIENT
 * TRACKING (ON/BCAST/PREFIX/REDIRECT/OFF) MUST fail gracefully — Dragonfly
 * v1.29.0 rejects FUNCTION/FCALL outright and rejects every CLIENT TRACKING
 * form under RESP2 (phpredis) with a clean ERR. Asserting those rejections
 * proves the fence holds and the connection survives the attempt.
 *
 * Findings recorded during P0.6.4 (kept OUT of the app surface):
 *   - OBJECT ENCODING  -> ERR unknown command `OBJECT`  (verified v1.29.0)
 *   - COPY             -> ERR unknown command `COPY`   (verified v1.29.0)
 * Both are listed in dragonfly_command_surface.php 'findings'; future code
 * must not call phpredis object()/copy() against Dragonfly.
 *
 * SKIP gate: Dragonfly unreachable => whole class skipped (never fails).
 * Summary numbers land in a per-run tempnam file (path printed to STDERR as
 * "dragonfly-surface-summary-path:") for the report.
 *
 * The command surface is only half the §13.5 story: RedisCommandSurfaceGuardTest
 * records what the app's REAL request path issues through the seam and asserts
 * it stays inside this pinned list. The manual P0.6.3 soak + P0.6.4
 * restart-mid-consume drill procedures live in SoakDrill.md (same directory).
 */
final class DragonflyCommandSurfaceTest extends TestCase
{
    private const SKIP_MESSAGE = 'Dragonfly not reachable on 127.0.0.1:6379 — start services with `docker compose up -d` in api/ (see api/docker/README.md).';

    private const SURFACE_FILE = __DIR__ . '/../Support/dragonfly_command_surface.php';

    private const EVALSHA_PLACEHOLDER = '__SHA1_RETURN_ARGV1__';

    private static string $summaryPath = '';

    public static function setUpBeforeClass(): void
    {
        if (!self::redisReachable()) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }

        self::$summaryPath = tempnam(sys_get_temp_dir(), 'kq-dragonfly-surface-summary-') ?: '';
        if (self::$summaryPath === '') {
            throw new RuntimeException('failed to reserve a summary file');
        }
        fwrite(STDERR, "dragonfly-surface-summary-path: " . self::$summaryPath . "\n");
    }

    public function testPinnedCommandSurfaceReturnsNoErrorReplies(): void
    {
        $surface = $this->loadSurface();
        $failures = [];

        // One connection for the whole replay: the MULTI/EXEC and WATCH
        // sequences only mean anything on a single connection, exactly like
        // the app's pool connections.
        $redis = self::connect();
        $run = function (array $entry) use ($redis, &$failures): void {
            [$command, $args] = $entry;
            $args = $this->resolvePlaceholders($args);
            $redis->clearLastError();
            $redis->rawCommand($command, ...$args);
            $err = $redis->getLastError();
            if ($err !== null) {
                $failures[] = ['command' => $command, 'args' => $args, 'error' => $err];
            }
        };

        // Seed runs first: deterministic test keys, each seed command must be
        // error-free too.
        foreach ($surface['seed'] as $entry) {
            $run($entry);
        }
        $seedFailures = $failures;
        $failures = [];

        foreach ($surface['commands'] as $entry) {
            $run($entry);
        }
        $commandFailures = $failures;
        $redis->close();

        $this->writeSummary([
            'seed_count' => count($surface['seed']),
            'command_count' => count($surface['commands']),
            'seed_errors' => count($seedFailures),
            'command_errors' => count($commandFailures),
            'pinned_command_entry_count' => $surface['meta']['pinned_command_entry_count'],
            'findings' => $surface['findings'],
        ]);

        // No magic floor: the pinned entry count lives in the surface file's
        // meta block (single source of truth) and must be bumped when the
        // surface legitimately grows. Dropping entries below it means the
        // replay silently covers less than what was verified.
        self::assertGreaterThanOrEqual(
            $surface['meta']['pinned_command_entry_count'],
            count($surface['commands']),
            'command surface must not shrink below the pinned entry count'
        );
        self::assertSame([], $seedFailures, 'seed commands must not error');
        self::assertSame([], $commandFailures, 'every pinned command must reply without an error');

        // Report failures loudly instead of papering over them.
        foreach (array_merge($seedFailures, $commandFailures) as $failure) {
            fwrite(STDERR, sprintf(
                "surface-finding: %s %s -> %s\n",
                $failure['command'],
                implode(' ', array_map('strval', $failure['args'])),
                $failure['error']
            ));
        }
    }

    public function testFenceHoldsAgainstUnsupportedFeatures(): void
    {
        $surface = $this->loadSurface();
        $redis = self::connect();
        $confirmed = [];

        foreach ($surface['fence'] as $name => [$command, $args]) {
            $redis->clearLastError();
            $redis->rawCommand($command, ...$args);
            $err = $redis->getLastError();
            self::assertNotNull(
                $err,
                "fence probe '$name' ($command) must FAIL on Dragonfly but succeeded — fence breached"
            );
            self::assertStringStartsWith(
                'ERR',
                (string) $err,
                "fence probe '$name' ($command) must fail with a graceful ERR reply"
            );
            $confirmed[] = ['probe' => $name, 'command' => $command, 'error' => $err];
            $redis->clearLastError();
        }

        // Graceful means the connection survives the rejected attempts.
        $redis->clearLastError();
        $redis->rawCommand('PING');
        self::assertNull($redis->getLastError(), 'connection must stay healthy after fence probes');
        $redis->close();

        $summary = $this->readSummary();
        $summary['fence_rejections_confirmed'] = $confirmed;
        $this->writeSummary($summary);
    }

    public function testPublishSubscribeRoundTrip(): void
    {
        $redis = self::connect();
        $channel = 'kq:surf:chan-rt';

        // Honor REDIS_HOST/REDIS_PORT like every other connection in the
        // suite (defaults match the docker compose bindings).
        $childHost = getenv('REDIS_HOST') ?: '127.0.0.1';
        $childPort = (int) (getenv('REDIS_PORT') ?: 6379);

        $childScript = <<<'PHP'
$r = new Redis();
$r->connect('HOST_PLACEHOLDER', PORT_PLACEHOLDER, 2);
$r->setOption(Redis::OPT_READ_TIMEOUT, 5);
$received = null;
try {
    $r->subscribe(['CHANNEL_PLACEHOLDER'], function ($redis, $chan, $msg) use (&$received) {
        $received = $msg;
        return false; // break the subscribe loop after the first message
    });
} catch (RedisException $e) {
    // phpredis raises a read error when the callback breaks the loop.
}
echo json_encode($received), PHP_EOL;
PHP;
        // Replace the QUOTED host placeholder (var_export adds its own
        // quotes); the port placeholder is a bare literal.
        $childScript = str_replace(
            ["'HOST_PLACEHOLDER'", 'PORT_PLACEHOLDER', 'CHANNEL_PLACEHOLDER'],
            [var_export($childHost, true), (string) $childPort, $channel],
            $childScript
        );

        $child = proc_open(
            [PHP_BINARY, '-r', $childScript],
            [
                0 => ['pipe', 'r'],
                1 => ['pipe', 'w'],
                2 => ['pipe', 'w'],
            ],
            $pipes
        );
        self::assertIsResource($child, 'subscribe child process must start');
        stream_set_timeout($pipes[1], 5);

        try {
            $subscribed = false;
            $deadline = microtime(true) + 5;
            while (microtime(true) < $deadline) {
                $counts = $redis->rawCommand('PUBSUB', 'NUMSUB', $channel);
                if (is_array($counts) && ($counts[1] ?? 0) >= 1) {
                    $subscribed = true;
                    break;
                }
                usleep(50_000);
            }
            self::assertTrue($subscribed, 'subscribe child must register before publish');

            $published = $redis->rawCommand('PUBLISH', $channel, 'roundtrip-' . bin2hex(random_bytes(3)));
            self::assertSame(1, $published, 'publish must reach exactly one subscriber');

            $line = fgets($pipes[1]);
            self::assertNotFalse($line, 'subscribe child must emit the received message');
            self::assertStringContainsString('roundtrip-', $line);
        } finally {
            $status = proc_get_status($child);
            if ($status['running']) {
                proc_terminate($child);
            }
            fclose($pipes[0]);
            fclose($pipes[1]);
            fclose($pipes[2]);
            proc_close($child);
            $redis->close();
        }
    }

    public function testRecorderDecoratorRecordsCommandsAndFailsOnUnknown(): void
    {
        $redis = self::connect();
        $recorder = new RedisCommandRecorder($redis);
        $recorder->reset();

        $recorder->set('kq:recorder:key', 'v1');
        $recorder->get('kq:recorder:key');
        $recorder->rawCommand('EXPIRE', 'kq:recorder:key', '60');

        self::assertCount(3, $recorder->trace());
        self::assertSame(['SET', 'GET', 'EXPIRE'], $recorder->commands());
        self::assertSame('EXPIRE', $recorder->trace()[2]['command']);
        self::assertSame(['kq:recorder:key', '60'], $recorder->trace()[2]['args']);

        // A command inside the verified list passes the §13.5 guard…
        $recorder->assertOnlyVerifiedCommands(['SET', 'GET', 'EXPIRE']);

        // …and one outside it fails loudly (the "can't silently grow" guard).
        try {
            $recorder->assertOnlyVerifiedCommands(['SET', 'GET']);
            self::fail('assertOnlyVerifiedCommands must throw for an unverified command');
        } catch (RuntimeException $e) {
            self::assertStringContainsString('EXPIRE', $e->getMessage());
        }

        $redis->rawCommand('DEL', 'kq:recorder:key');
        $redis->close();
    }

    /**
     * @return array<string, mixed>
     */
    private function loadSurface(): array
    {
        $surface = require self::SURFACE_FILE;
        self::assertIsArray($surface);
        self::assertArrayHasKey('seed', $surface);
        self::assertArrayHasKey('commands', $surface);
        self::assertArrayHasKey('fence', $surface);
        self::assertArrayHasKey('documented_skips', $surface);

        return $surface;
    }

    /**
     * @param list<mixed> $args
     * @return list<mixed>
     */
    private function resolvePlaceholders(array $args): array
    {
        $sha = sha1('return ARGV[1]'); // Redis's script fingerprint

        return array_map(
            static fn (mixed $arg): mixed => $arg === self::EVALSHA_PLACEHOLDER ? $sha : $arg,
            $args
        );
    }

    private static function connect(): Redis
    {
        $redis = new Redis();
        $redis->connect(
            getenv('REDIS_HOST') ?: '127.0.0.1',
            (int) (getenv('REDIS_PORT') ?: 6379),
            2
        );
        $redis->setOption(Redis::OPT_READ_TIMEOUT, 5);

        return $redis;
    }

    private static function redisReachable(): bool
    {
        $host = getenv('REDIS_HOST') ?: '127.0.0.1';
        $port = (int) (getenv('REDIS_PORT') ?: 6379);
        $socket = @fsockopen($host, $port, $errno, $errstr, 2);
        if (is_resource($socket)) {
            fclose($socket);

            return true;
        }

        return false;
    }

    /**
     * @return array<string, mixed>
     */
    private function readSummary(): array
    {
        if (self::$summaryPath === '' || !file_exists(self::$summaryPath)) {
            return [];
        }

        $data = json_decode((string) file_get_contents(self::$summaryPath), true);

        return is_array($data) ? $data : [];
    }

    /**
     * @param array<string, mixed> $summary
     */
    private function writeSummary(array $summary): void
    {
        file_put_contents(
            self::$summaryPath,
            json_encode($summary, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n"
        );
    }
}