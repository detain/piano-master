<?php

declare(strict_types=1);

namespace Tests\Integration;

use GuzzleHttp\Client;
use PHPUnit\Framework\TestCase;
use RuntimeException;
use Throwable;

/**
 * P0.6.1 skeleton route integration test (plan §13.7).
 *
 * Boots a REAL Webman instance on an ephemeral port in a child process and
 * hits it over HTTP with Guzzle — no framework test harness. Requires MySQL +
 * Dragonfly reachable (api/docker-compose.yml). If they are not reachable the
 * whole class SKIPS (never fails), so the suite stays green in CI without
 * services; locally with docker up it fully passes.
 */
final class SkeletonRoutesTest extends TestCase
{
    private const SKIP_MESSAGE = 'MySQL/Dragonfly not reachable — start services with `docker compose up -d` in api/ (see api/docker/README.md).';

    private const BOOT_TIMEOUT_SECONDS = 20;

    private static mixed $serverProcess = null;
    private static string $serverLogFile = '';
    private static string $baseUrl = '';

    public static function setUpBeforeClass(): void
    {
        if (!self::dependenciesReachable()) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }

        $port = self::findFreePort();
        self::$baseUrl = 'http://127.0.0.1:' . $port;
        self::$serverLogFile = sys_get_temp_dir() . '/kq-integration-' . getmypid() . '.log';

        $env = getenv();
        $env['WEBMAN_LISTEN'] = 'http://127.0.0.1:' . $port;
        $env['DEV_API_TOKEN'] = 'dev-token';

        self::$serverProcess = proc_open(
            [PHP_BINARY, 'start.php', 'start'],
            [
                0 => ['pipe', 'r'],
                1 => ['file', self::$serverLogFile, 'a'],
                2 => ['file', self::$serverLogFile, 'a'],
            ],
            $pipes,
            dirname(__DIR__, 2), // api/ — tests/Integration -> api
            $env
        );

        if (!is_resource(self::$serverProcess)) {
            throw new RuntimeException('Failed to start Webman child process');
        }

        self::waitForServer();
    }

    public static function tearDownAfterClass(): void
    {
        if (!is_resource(self::$serverProcess)) {
            return;
        }
        proc_terminate(self::$serverProcess);
        $deadline = microtime(true) + 5;
        while (microtime(true) < $deadline) {
            $status = proc_get_status(self::$serverProcess);
            if (!$status['running']) {
                break;
            }
            usleep(100_000);
        }
        proc_close(self::$serverProcess);
        self::$serverProcess = null;
    }

    public function testRootReturnsStaticJson(): void
    {
        $response = $this->client()->get('/');

        self::assertSame(200, $response->getStatusCode());
        self::assertNotEmpty($response->getHeaderLine('X-Request-Id'), 'X-Request-Id header must be echoed');
        $data = $this->decode($response);
        self::assertSame('keyquest-api', $data['service']);
        self::assertTrue($data['ok']);
    }

    public function testHealthzReturnsOk(): void
    {
        $response = $this->client()->get('/healthz');

        self::assertSame(200, $response->getStatusCode());
        $data = $this->decode($response);
        self::assertSame('ok', $data['status']);
    }

    public function testReadyzReflectsDependencies(): void
    {
        $response = $this->client()->get('/readyz');
        $status = $response->getStatusCode();

        // 200 when both services are up, 503 when one is down — both shapes valid.
        self::assertContains($status, [200, 503], 'readiness must be 200 (ready) or 503 (not ready)');
        $data = $this->decode($response);
        self::assertArrayHasKey('status', $data);
        self::assertArrayHasKey('mysql', $data);
        self::assertArrayHasKey('dragonfly', $data);

        if ($status === 200) {
            self::assertTrue($data['mysql']);
            self::assertTrue($data['dragonfly']);
        }
    }

    public function testDbVersionReadsMysql(): void
    {
        $response = $this->client()->get('/db/version');

        self::assertSame(200, $response->getStatusCode());
        $data = $this->decode($response);
        self::assertNotEmpty($data['db_version']);
    }

    public function testCacheNowReadThrough(): void
    {
        $first = $this->client()->get('/cache/now');
        self::assertSame(200, $first->getStatusCode());
        $firstData = $this->decode($first);
        self::assertNotEmpty($firstData['value']);

        // Within the 60s TTL a second call must return the same cached value.
        $second = $this->client()->get('/cache/now');
        self::assertSame(200, $second->getStatusCode());
        $secondData = $this->decode($second);
        self::assertSame($firstData['value'], $secondData['value']);
    }

    public function testAuthEchoAuthenticatedWrite(): void
    {
        $message = 'integration test ' . uniqid('', true);
        $response = $this->client()->post('/auth/echo', [
            'headers' => ['Authorization' => 'Bearer dev-token'],
            'json' => ['message' => $message],
        ]);

        self::assertSame(200, $response->getStatusCode());
        $data = $this->decode($response);
        self::assertNotEmpty($data['id']);
        self::assertSame($message, $data['message']);
    }

    public function testAuthEchoRejectsMissingToken(): void
    {
        $response = $this->client()->post('/auth/echo', [
            'json' => ['message' => 'nope'],
        ]);

        self::assertSame(401, $response->getStatusCode());
        $data = $this->decode($response);
        self::assertSame('unauthorized', $data['error']);
    }

    public function testAuthEchoRejectsWrongToken(): void
    {
        $response = $this->client()->post('/auth/echo', [
            'headers' => ['Authorization' => 'Bearer wrong-token'],
            'json' => ['message' => 'nope'],
        ]);

        self::assertSame(401, $response->getStatusCode());
        $data = $this->decode($response);
        self::assertSame('unauthorized', $data['error']);
    }

    private function client(): Client
    {
        return new Client([
            'base_uri' => self::$baseUrl,
            'timeout' => 5,
            'connect_timeout' => 2,
            'http_errors' => false,
        ]);
    }

    /**
     * @return array<string, mixed>
     */
    private function decode($response): array
    {
        $data = json_decode((string) $response->getBody(), true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($data);

        return $data;
    }

    /**
     * TCP-probe MySQL and Dragonfly with short timeouts. This is the SKIP
     * gate: unreachable services => skipped class, never a failure.
     */
    private static function dependenciesReachable(): bool
    {
        $dbHost = getenv('DB_HOST') ?: '127.0.0.1';
        $dbPort = (int) (getenv('DB_PORT') ?: 3306);
        $redisHost = getenv('REDIS_HOST') ?: '127.0.0.1';
        $redisPort = (int) (getenv('REDIS_PORT') ?: 6379);

        return self::tcpReachable($dbHost, $dbPort)
            && self::tcpReachable($redisHost, $redisPort);
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

    /**
     * Poll GET /healthz until the child serves HTTP or the boot deadline hits.
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
            $status = proc_get_status(self::$serverProcess);
            if (!$status['running']) {
                throw new RuntimeException(
                    'Webman child process exited during boot; see ' . self::$serverLogFile
                );
            }
            try {
                if ($client->get('/healthz')->getStatusCode() === 200) {
                    return;
                }
            } catch (Throwable) {
                // Not up yet — keep polling.
            }
            usleep(200_000);
        }

        throw new RuntimeException(
            'Webman child process did not become ready within ' . self::BOOT_TIMEOUT_SECONDS
            . 's; see ' . self::$serverLogFile
        );
    }
}