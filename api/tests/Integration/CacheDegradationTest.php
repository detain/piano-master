<?php

declare(strict_types=1);

namespace Tests\Integration;

use GuzzleHttp\Client;
use PHPUnit\Framework\TestCase;
use RuntimeException;

/**
 * CacheDegradationTest — P0.6.6 graceful-degradation proof.
 *
 * Boots a REAL Webman child with REDIS_PORT pointed at a closed port (i.e.
 * Dragonfly is unreachable by construction — no docker kill needed) and
 * asserts the cache endpoint degrades instead of 500ing:
 *
 *   (a) GET /cache/now returns 200 with `degraded: true` and a usable value
 *       (CacheGuard + CacheController fallback),
 *   (b) GET /readyz returns 503 with dragonfly:false — readiness honestly
 *       reports the outage (this is the drill-B assertion),
 *   (c) routes that do NOT touch Dragonfly (/, /healthz, /db/version) keep
 *       serving 200 — the outage is contained to the cache path,
 *   (d) repeated /cache/now calls stay degraded-200 (the pool does not
 *       wedge on the first failure).
 *
 * Boot uses WebmanTestHarness::boot([...], requireReady: false) because
 * /readyz is deliberately NOT ready here; the harness waits on /healthz
 * (liveness) instead. No dependency skip gate: the whole point is an
 * unreachable cache, and /db/version needs MySQL, but the test asserts the
 * MySQL-independent surface only — it stays green even with MySQL down.
 */
final class CacheDegradationTest extends TestCase
{
    private const SKIP_MESSAGE = 'Cannot reserve a closed port for the degraded-Dragonfly boot.';

    public static function setUpBeforeClass(): void
    {
        $closedPort = self::reserveClosedPort();
        if ($closedPort === null) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }

        WebmanTestHarness::boot(
            ['REDIS_PORT' => (string) $closedPort],
            false
        );
    }

    public static function tearDownAfterClass(): void
    {
        WebmanTestHarness::shutdown();
    }

    public function testCacheNowDegradesInsteadOf500(): void
    {
        $first = $this->client()->get('/cache/now');

        self::assertSame(200, $first->getStatusCode(), 'cache endpoint must degrade, not 500, when Dragonfly is unreachable');
        $firstData = $this->decode($first);
        self::assertTrue($firstData['degraded'], 'degraded flag must be set on the fallback response');
        self::assertNotEmpty($firstData['value'], 'degraded value falls back to the local clock');
        self::assertFalse($firstData['from_cache']);

        // The pool must not wedge: a second call degrades the same way.
        $second = $this->client()->get('/cache/now');
        self::assertSame(200, $second->getStatusCode());
        self::assertTrue($this->decode($second)['degraded']);
    }

    public function testReadyzReportsNotReadyWhenDragonflyDown(): void
    {
        $response = $this->client()->get('/readyz');

        self::assertSame(503, $response->getStatusCode());
        $data = $this->decode($response);
        self::assertFalse($data['dragonfly'], 'readiness must report the Dragonfly outage');
    }

    public function testNonCacheRoutesKeepServing(): void
    {
        self::assertSame(200, $this->client()->get('/')->getStatusCode());
        self::assertSame(200, $this->client()->get('/healthz')->getStatusCode());
        self::assertSame(200, $this->client()->get('/db/version')->getStatusCode());
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

    /**
     * Reserve an ephemeral port and immediately release it, so the child's
     * REDIS_PORT points at a definitely-closed socket (modulo an unrelated
     * process grabbing the port in the race window — acceptable for tests).
     */
    private static function reserveClosedPort(): ?int
    {
        $server = @stream_socket_server('tcp://127.0.0.1:0', $errno, $errstr);
        if (!$server) {
            return null;
        }
        $name = stream_socket_get_name($server, false);
        fclose($server);

        return (int) substr(strrchr($name, ':'), 1);
    }
}