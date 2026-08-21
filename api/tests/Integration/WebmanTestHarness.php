<?php

declare(strict_types=1);

namespace Tests\Integration;

use GuzzleHttp\Client;
use RuntimeException;
use Throwable;

/**
 * WebmanTestHarness — shared child-process boot for the integration suite.
 *
 * Plan §13.7: no framework test harness. Each integration test class boots a
 * REAL Webman instance on an ephemeral port in a child process (php start.php
 * start with WEBMAN_LISTEN overridden) and hits it over HTTP with Guzzle.
 * Boot waits for GET /readyz == 200 so a freshly started docker compose
 * stack (mysql-init still seeding) is waited on instead of failed on.
 *
 * Usage from a PHPUnit test class:
 *
 *   public static function setUpBeforeClass(): void
 *   {
 *       if (!WebmanTestHarness::dependenciesReachable()) {
 *           self::markTestSkipped(self::SKIP_MESSAGE);
 *       }
 *       WebmanTestHarness::boot();
 *   }
 *
 *   public static function tearDownAfterClass(): void
 *   {
 *       WebmanTestHarness::shutdown();
 *   }
 *
 * State: one static boot slot — the PHPUnit runner executes classes
 * sequentially, so boot() in setUpBeforeClass + shutdown() in
 * tearDownAfterClass never overlaps.
 */
final class WebmanTestHarness
{
    private const BOOT_TIMEOUT_SECONDS = 30;

    private static mixed $process = null;
    private static string $logFile = '';
    private static string $baseUrl = '';
    private static int $masterPid = 0;

    private function __construct()
    {
    }

    /**
     * Boot the child Webman worker and wait until GET /readyz is 200.
     *
     * @throws RuntimeException when the child dies during boot or the boot
     *                          deadline expires (log path is in the message).
     */
    public static function boot(): void
    {
        $port = self::findFreePort();
        self::$baseUrl = 'http://127.0.0.1:' . $port;
        self::$logFile = sys_get_temp_dir() . '/kq-integration-' . getmypid() . '.log';

        $env = getenv();
        $env['WEBMAN_LISTEN'] = 'http://127.0.0.1:' . $port;
        $env['DEV_API_TOKEN'] = 'dev-token';

        self::$process = proc_open(
            [PHP_BINARY, 'start.php', 'start'],
            [
                0 => ['pipe', 'r'],
                1 => ['file', self::$logFile, 'a'],
                2 => ['file', self::$logFile, 'a'],
            ],
            $pipes,
            dirname(__DIR__, 2), // tests/Integration -> api/
            $env
        );

        if (!is_resource(self::$process)) {
            throw new RuntimeException('Failed to start Webman child process');
        }

        self::$masterPid = (int) proc_get_status(self::$process)['pid'];
        self::waitForServer();
    }

    /**
     * Cooperative SIGTERM, escalating to SIGKILL so teardown always returns.
     */
    public static function shutdown(): void
    {
        if (!is_resource(self::$process)) {
            return;
        }

        proc_terminate(self::$process);
        self::waitForExit(5);

        $status = proc_get_status(self::$process);
        if ($status['running']) {
            proc_terminate(self::$process, 9);
            self::waitForExit(2);
        }

        proc_close(self::$process);
        self::$process = null;
        self::$masterPid = 0;
    }

    public static function isRunning(): bool
    {
        if (!is_resource(self::$process)) {
            return false;
        }

        return (bool) proc_get_status(self::$process)['running'];
    }

    public static function baseUrl(): string
    {
        return self::$baseUrl;
    }

    public static function logFile(): string
    {
        return self::$logFile;
    }

    public static function masterPid(): int
    {
        return self::$masterPid;
    }

    /**
     * Worker child PIDs of the master (Workerman forks one process per
     * `count` in config/server.php; this skeleton runs count=1).
     *
     * @return list<int>
     */
    public static function workerPids(): array
    {
        if (self::$masterPid <= 0) {
            return [];
        }

        $childrenFile = '/proc/' . self::$masterPid . '/task/' . self::$masterPid . '/children';
        $contents = @file_get_contents($childrenFile);
        if ($contents === false) {
            return [];
        }

        $pids = [];
        foreach (preg_split('/\s+/', trim($contents)) ?: [] as $token) {
            if (ctype_digit($token)) {
                $pids[] = (int) $token;
            }
        }

        return $pids;
    }

    /**
     * Max VmRSS across the worker children (the processes that actually serve
     * requests), falling back to the master when /proc hides the children.
     * Returns null when /proc is unavailable (non-Linux CI).
     */
    public static function workerRssKb(): ?int
    {
        $pids = self::workerPids();
        if ($pids === []) {
            $pids = [self::$masterPid];
        }

        $maxKb = null;
        foreach (array_unique($pids) as $pid) {
            $kb = self::readVmRssKb($pid);
            if ($kb !== null) {
                $maxKb = $maxKb === null ? $kb : max($maxKb, $kb);
            }
        }

        return $maxKb;
    }

    /**
     * TCP-probe MySQL and Dragonfly with short timeouts. This is the SKIP
     * gate: unreachable services => skipped class, never a failure.
     */
    public static function dependenciesReachable(): bool
    {
        $dbHost = getenv('DB_HOST') ?: '127.0.0.1';
        $dbPort = (int) (getenv('DB_PORT') ?: 3306);
        $redisHost = getenv('REDIS_HOST') ?: '127.0.0.1';
        $redisPort = (int) (getenv('REDIS_PORT') ?: 6379);

        return self::tcpReachable($dbHost, $dbPort)
            && self::tcpReachable($redisHost, $redisPort);
    }

    /**
     * Poll GET /readyz (expect 200) until the child serves HTTP or the boot
     * deadline hits.
     */
    private static function waitForServer(): void
    {
        $client = new Client([
            'base_uri' => self::$baseUrl,
            'timeout' => 2,
            'connect_timeout' => 1,
            'http_errors' => false,
        ]);

        $deadline = microtime(true) + self::BOOT_TIMEOUT_SECONDS;
        while (microtime(true) < $deadline) {
            $status = proc_get_status(self::$process);
            if (!$status['running']) {
                throw new RuntimeException(
                    'Webman child process exited during boot; see ' . self::$logFile
                );
            }
            try {
                if ($client->get('/readyz')->getStatusCode() === 200) {
                    return;
                }
            } catch (Throwable) {
                // Not up yet — keep polling.
            }
            usleep(200_000);
        }

        throw new RuntimeException(
            'Webman child process did not become ready within ' . self::BOOT_TIMEOUT_SECONDS
            . 's; see ' . self::$logFile
        );
    }

    private static function waitForExit(int $seconds): void
    {
        $deadline = microtime(true) + $seconds;
        while (microtime(true) < $deadline) {
            $status = proc_get_status(self::$process);
            if (!$status['running']) {
                return;
            }
            usleep(100_000);
        }
    }

    private static function tcpReachable(string $host, int $port): bool
    {
        $errno = 0;
        $errstr = '';
        $socket = @fsockopen($host, $port, $errno, $errstr, 2);
        if (is_resource($socket)) {
            fclose($socket);

            return true;
        }

        return false;
    }

    private static function findFreePort(): int
    {
        $server = stream_socket_server('tcp://127.0.0.1:0', $errno, $errstr);
        if (!$server) {
            throw new RuntimeException("Unable to reserve ephemeral port: $errstr");
        }
        $name = stream_socket_get_name($server, false);
        fclose($server);
        $port = (int) substr(strrchr($name, ':'), 1);

        return $port;
    }

    private static function readVmRssKb(int $pid): ?int
    {
        $status = @file_get_contents('/proc/' . $pid . '/status');
        if ($status === false) {
            return null;
        }
        if (!preg_match('/^VmRSS:\s+(\d+) kB$/m', $status, $matches)) {
            return null;
        }

        return (int) $matches[1];
    }
}