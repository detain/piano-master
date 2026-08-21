<?php

declare(strict_types=1);

namespace Tests\Integration;

use GuzzleHttp\Client;
use PHPUnit\Framework\TestCase;

/**
 * P0.6.1 skeleton route integration test (plan §13.7).
 *
 * Boots a REAL Webman instance on an ephemeral port in a child process and
 * hits it over HTTP with Guzzle — no framework test harness. The child-process
 * boot (port reservation, WEBMAN_LISTEN env override, /readyz wait, teardown)
 * lives in WebmanTestHarness, shared with WorkerLongevityTest. Requires MySQL +
 * Dragonfly reachable (api/docker-compose.yml). If they are not reachable the
 * whole class SKIPS (never fails), so the suite stays green in CI without
 * services; locally with docker up it fully passes.
 */
final class SkeletonRoutesTest extends TestCase
{
    private const SKIP_MESSAGE = 'MySQL/Dragonfly not reachable — start services with `docker compose up -d` in api/ (see api/docker/README.md).';

    public static function setUpBeforeClass(): void
    {
        if (!WebmanTestHarness::dependenciesReachable()) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }

        WebmanTestHarness::boot();
    }

    public static function tearDownAfterClass(): void
    {
        WebmanTestHarness::shutdown();
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
            'base_uri' => WebmanTestHarness::baseUrl(),
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
}