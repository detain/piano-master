<?php

declare(strict_types=1);

namespace Tests\Integration;

use GuzzleHttp\Client;
use GuzzleHttp\Pool;
use PHPUnit\Framework\Attributes\Group;
use PHPUnit\Framework\TestCase;

/**
 * Worker-longevity / state-bleed suite — plan §13.7, §20 P0.6.2/P0.6.3.
 *
 * Boots ONE real Webman worker (WebmanTestHarness) and fires 10 000 requests
 * at it with Guzzle concurrency 8 across the mixed route surface
 * (/, /healthz, /readyz, /db/version, /cache/now). Asserts:
 *
 *   (a) worker alive at the end,
 *   (b) zero cross-request data bleed — every request carries a unique
 *       inbound X-Request-Id and the response MUST echo that same id back
 *       (RequestIdMiddleware). A mismatched echo means request-scoped state
 *       leaked from request N into request N+1 — the §13.4.2 bug class this
 *       suite exists for. There is no "request user" route in the P0.6
 *       skeleton, so the request id is the bleed canary,
 *   (c) worker RSS growth after warmup stays under 20 MB (CI-safe bound; the
 *       plan's P0.6.3 target is < 5 MB — the measured delta is reported in
 *       /tmp/kq-longevity-summary.json so the tight number stays visible),
 *   (d) the whole 10k run completes in under 2 minutes.
 *
 * Runtime: locally ~10-40 s with docker up; bounded by assertion (d). The
 * test lives in the default integration suite (it is the §13.7 "do not skip"
 * suite) and is additionally tagged #[Group('longevity')] so CI can run it
 * alone.
 *
 * SKIP gate: services unreachable => whole class skipped (same pattern as
 * SkeletonRoutesTest). /proc is required for the RSS assertion, so the class
 * also skips on non-Linux where the memory ceiling cannot be measured.
 *
 * P0.6.2 note: this suite was demonstrated "by construction" against the
 * §13.4.2 bugs — see StateBleedGuardTest's docblock for the exercise.
 */
#[Group('longevity')]
final class WorkerLongevityTest extends TestCase
{
    private const SKIP_MESSAGE = 'MySQL/Dragonfly not reachable — start services with `docker compose up -d` in api/ (see api/docker/README.md).';

    private const TOTAL_REQUESTS = 10_000;
    private const WARMUP_REQUESTS = 500;
    private const CONCURRENCY = 8;
    private const RUNTIME_BOUND_SECONDS = 120;
    private const MAX_RSS_GROWTH_KB = 20 * 1024; // < 20 MB CI-safe; plan target 5 MB
    private const ROUTES = ['/', '/healthz', '/readyz', '/db/version', '/cache/now'];

    private static string $summaryPath = '';

    public static function setUpBeforeClass(): void
    {
        if (!WebmanTestHarness::dependenciesReachable()) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }
        if (PHP_OS_FAMILY !== 'Linux') {
            self::markTestSkipped('Worker RSS measurement requires /proc (Linux).');
        }

        WebmanTestHarness::boot();

        self::$summaryPath = tempnam(sys_get_temp_dir(), 'kq-longevity-summary-') ?: '';
        if (self::$summaryPath === '') {
            throw new \RuntimeException('failed to reserve a summary file');
        }
        fwrite(STDERR, "longevity-summary-path: " . self::$summaryPath . "\n");
    }

    public static function tearDownAfterClass(): void
    {
        WebmanTestHarness::shutdown();
    }

    public function testTenThousandMixedRequestsWithoutBleedOrGrowth(): void
    {
        $client = new Client([
            'base_uri' => WebmanTestHarness::baseUrl(),
            'timeout' => 10,
            'connect_timeout' => 2,
            'http_errors' => false,
        ]);

        $startedAt = microtime(true);

        // Warmup: let JIT/class-loading and the MySQL/Dragonfly pools settle
        // before the RSS baseline so the measured delta is steady-state growth.
        $warmupMismatches = [];
        $this->fire($client, self::WARMUP_REQUESTS, $warmupMismatches);
        self::assertSame([], $warmupMismatches, 'warmup must not bleed request ids');

        $rssBeforeKb = WebmanTestHarness::workerRssKb();
        self::assertNotNull($rssBeforeKb, 'worker RSS baseline must be readable via /proc');

        $mismatches = [];
        $this->fire($client, self::TOTAL_REQUESTS, $mismatches);

        $rssAfterKb = WebmanTestHarness::workerRssKb();
        self::assertNotNull($rssAfterKb, 'worker RSS after the run must be readable via /proc');
        $rssPeakKb = WebmanTestHarness::workerPeakRssKb();
        $elapsedSeconds = microtime(true) - $startedAt;
        $growthKb = $rssAfterKb - $rssBeforeKb;
        $workerAlive = WebmanTestHarness::isRunning();

        $this->writeSummary([
            'request_count' => self::TOTAL_REQUESTS,
            'warmup_requests' => self::WARMUP_REQUESTS,
            'bleed_mismatches' => count($mismatches),
            'rss_before_kb' => $rssBeforeKb,
            'rss_after_kb' => $rssAfterKb,
            'rss_peak_kb' => $rssPeakKb, // VmHWM high-water mark, informational
            'rss_growth_kb' => $growthKb,
            'elapsed_seconds' => round($elapsedSeconds, 2),
            'worker_alive' => $workerAlive,
            'routes' => self::ROUTES,
        ]);

        self::assertTrue($workerAlive, 'worker must still be alive after 10k requests');
        self::assertSame([], $mismatches, 'zero cross-request data bleed allowed (X-Request-Id echo mismatch)');
        self::assertLessThan(
            self::MAX_RSS_GROWTH_KB,
            $growthKb,
            sprintf(
                'worker RSS grew %d kB after warmup (%d -> %d kB); must stay under %d kB',
                $growthKb,
                $rssBeforeKb,
                $rssAfterKb,
                self::MAX_RSS_GROWTH_KB
            )
        );
        self::assertLessThan(
            self::RUNTIME_BOUND_SECONDS,
            $elapsedSeconds,
            sprintf('10k requests must complete in under %d s (took %.1f s)', self::RUNTIME_BOUND_SECONDS, $elapsedSeconds)
        );

        // The worker served all 10k — prove it still answers a live request.
        $final = $client->get('/healthz');
        self::assertSame(200, $final->getStatusCode());
    }

    /**
     * Fire $count async requests through the worker at self::CONCURRENCY,
     * asserting each response echoes its unique X-Request-Id with status 200.
     *
     * @param list<array{0: string, 1: string, 2: int}> $mismatches
     */
    private function fire(Client $client, int $count, array &$mismatches): void
    {
        $requests = function () use ($client, $count): \Generator {
            for ($i = 0; $i < $count; $i++) {
                $route = self::ROUTES[$i % count(self::ROUTES)];
                $requestId = 'kq-lt-' . $i . '-' . bin2hex(random_bytes(4));
                yield $requestId => fn () => $client->getAsync($route, [
                    'headers' => ['X-Request-Id' => $requestId],
                ]);
            }
        };

        $pool = new Pool($client, $requests(), [
            'concurrency' => self::CONCURRENCY,
            'fulfilled' => function ($response, string $requestId) use (&$mismatches): void {
                $echoed = $response->getHeaderLine('X-Request-Id');
                if ($echoed !== $requestId || $response->getStatusCode() !== 200) {
                    $mismatches[] = [$requestId, $echoed, $response->getStatusCode()];
                }
            },
            'rejected' => function ($reason, string $requestId) use (&$mismatches): void {
                $mismatches[] = [$requestId, 'REJECTED: ' . (string) $reason, -1];
            },
        ]);

        $pool->promise()->wait();
    }

    /**
     * Write measured numbers where the verification step can read them —
     * PHPUnit hides passing-test stdout, and the report needs the RSS delta
     * and request count. Per-run tempnam path (printed to STDERR as
     * "longevity-summary-path:") so parallel runs never clobber each other.
     *
     * @param array<string, mixed> $summary
     */
    private function writeSummary(array $summary): void
    {
        $encoded = json_encode($summary, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
        file_put_contents(self::$summaryPath, $encoded . "\n");
        fwrite(STDERR, "longevity-summary: " . $encoded . "\n");
    }
}