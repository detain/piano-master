<?php

declare(strict_types=1);

namespace Tests\Integration;

use GuzzleHttp\Client;
use PHPUnit\Framework\TestCase;
use RuntimeException;
use Tests\Support\RedisCommandRecorder;

/**
 * Redis command-surface guard — plan §13.5 "can't silently grow", exercised
 * against the app's REAL request path.
 *
 * DragonflyCommandSurfaceTest replays the pinned list; this test proves the
 * commands the app ACTUALLY issues stay inside it. It boots the Webman child
 * with the RedisRecordBootstrap seam (config/bootstrap.php) enabled, which
 * wraps every pooled redis connection in a RedisCommandRecorder and issues
 * one redis-queue producer round through the vendored client. The test then:
 *
 *   (a) exercises the real cache route twice — a miss (GET + SET NX EX + read
 *       back GET) and a hit (GET) — plus /readyz PINGs already issued during
 *       boot,
 *   (b) shuts the child down (the seam flushes the merged trace to a per-run
 *       temp file as the worker stops),
 *   (c) asserts the recorded command set is a SUBSET of the pinned surface
 *       via RedisCommandRecorder::assertOnlyVerifiedCommands(),
 *   (d) asserts the seam really captured the app's traffic (GET/SET must be
 *       present, plus the queue producer's LPUSH) so an unwrapped client can
 *       never produce a vacuous pass.
 *
 * Commands recorded today: PING (readyz), GET/SET (cache read-through),
 * LPUSH/ZADD (queue producer round) — all pinned. Any future route that calls
 * an unpinned command fails here loudly, which is the point.
 *
 * SKIP gate: services unreachable => whole class skipped (never fails).
 * Manual soak/drill procedures: see SoakDrill.md in this directory.
 */
final class RedisCommandSurfaceGuardTest extends TestCase
{
    private const SKIP_MESSAGE = 'MySQL/Dragonfly not reachable — start services with `docker compose up -d` in api/ (see api/docker/README.md).';

    private const SURFACE_FILE = __DIR__ . '/../Support/dragonfly_command_surface.php';

    private static string $traceFile = '';

    public static function setUpBeforeClass(): void
    {
        if (!WebmanTestHarness::dependenciesReachable()) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }

        self::$traceFile = tempnam(sys_get_temp_dir(), 'kq-redis-trace-') ?: '';
        if (self::$traceFile === '') {
            throw new RuntimeException('failed to reserve a trace file');
        }
        fwrite(STDERR, 'redis-guard-trace: ' . self::$traceFile . "\n");

        WebmanTestHarness::boot([
            'KQ_TEST_RECORD_REDIS' => '1',
            'KQ_TEST_REDIS_TRACE_FILE' => self::$traceFile,
            'KQ_TEST_REDIS_QUEUE_PRODUCE' => '1',
        ]);
    }

    public static function tearDownAfterClass(): void
    {
        WebmanTestHarness::shutdown();
    }

    public function testAppRedisUsageStaysInsidePinnedSurface(): void
    {
        // Deterministic cache MISS: delete the read-through key from the
        // parent so the child's first GET /cache/now is guaranteed to take
        // the SET NX + read-back path (a stale TTL from a previous run would
        // otherwise turn it into a hit and the SET assertion would be
        // vacuous). The DEL happens in the parent process — never recorded.
        $cleanup = new \Redis();
        $cleanup->connect(
            getenv('REDIS_HOST') ?: '127.0.0.1',
            (int) (getenv('REDIS_PORT') ?: 6379),
            2
        );
        $cleanup->del('kq:boot_time');
        $cleanup->close();

        $client = new Client([
            'base_uri' => WebmanTestHarness::baseUrl(),
            'timeout' => 5,
            'connect_timeout' => 2,
            'http_errors' => false,
        ]);

        // First call: cache miss — GET, then SET NX EX 60, then read-back GET.
        $first = $client->get('/cache/now');
        self::assertSame(200, $first->getStatusCode());
        // Second call: cache hit — GET only.
        $second = $client->get('/cache/now');
        self::assertSame(200, $second->getStatusCode());

        // The trace is flushed by the seam when the worker stops; shutdown()
        // waits for the child to exit, so the file is complete when it returns.
        WebmanTestHarness::shutdown();

        $trace = $this->readTrace();
        self::assertNotEmpty(
            $trace,
            'recorder trace is empty — the RedisRecordBootstrap seam did not wrap the app redis client'
        );

        // Prove the wiring captured the app's real traffic, not just the
        // queue round: GET+SET are the cache route's commands, LPUSH the
        // producer round. Without these the subset check would pass vacuously.
        $commandNames = array_values(array_unique(array_map(
            static fn (array $entry): string => $entry['command'],
            $trace
        )));
        self::assertContains('GET', $commandNames, 'GET must be recorded through the seam');
        self::assertContains('SET', $commandNames, 'SET must be recorded through the seam');
        self::assertContains('LPUSH', $commandNames, 'queue producer LPUSH must be recorded through the seam');

        $surface = require self::SURFACE_FILE;
        $verifiedNames = array_values(array_unique(array_map(
            static fn (array $entry): string => strtoupper((string) $entry[0]),
            $surface['commands']
        )));

        $recorder = RedisCommandRecorder::fromTrace($trace);
        $recorder->assertOnlyVerifiedCommands(
            $verifiedNames,
            'App Redis usage grew outside the pinned Dragonfly surface (see api/tests/Support/dragonfly_command_surface.php)'
        );

        fwrite(STDERR, 'redis-guard: recorded ' . implode(', ', $commandNames) . "\n");
    }

    /**
     * @return list<array{command: string, args: list<mixed>}>
     */
    private function readTrace(): array
    {
        if (!is_file(self::$traceFile)) {
            self::fail('trace file missing after child shutdown — the seam did not flush; see ' . WebmanTestHarness::logFile());
        }

        $data = json_decode((string) file_get_contents(self::$traceFile), true);
        self::assertIsArray($data, 'trace file is not valid JSON; see ' . WebmanTestHarness::logFile());

        return $data['trace'] ?? [];
    }
}