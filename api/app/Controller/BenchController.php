<?php

namespace App\Controller;

use GuzzleHttp\Client;
use RuntimeException;
use support\Request;
use support\Response;
use Throwable;
use Workerman\Coroutine;
use Workerman\Coroutine\Parallel;
use Workerman\Http\Client as AsyncClient;

/**
 * GET /bench/outbound — PHASE-0 SPIKE ONLY (P0.6.7 coroutine posture).
 *
 * Measures outbound-HTTP fan-out inside the HTTP worker two ways:
 *
 *   mode=blocking — $fanout SEQUENTIAL Guzzle requests to the mock upstream.
 *                   Each blocks the worker thread for fanout x upstream
 *                   latency; 4 workers means 4 concurrent API requests.
 *
 *   mode=fiber    — $fanout CONCURRENT fibers (Workerman\Coroutine) each
 *                   issuing one workerman/http-client request, joined with
 *                   Workerman\Coroutine\Parallel (the Barrier join, which is
 *                   deadlock-free here; see fiberFanout() for why not
 *                   WaitGroup). Under the Fiber event loop the worker thread
 *                   never blocks on upstream I/O; 4 workers sustain 4 API
 *                   requests concurrently, each fanning out N upstream calls.
 *
 * The fiber mode REQUIRES the Fiber (Revolt) event loop: its blocking
 * Channel/Barrier primitives are what let the handler join the fan-out from
 * inside a coroutine context:
 *
 *   WEBMAN_EVENT_LOOP="Workerman\Events\Fiber" php start.php start
 *
 * (config/server.php reads WEBMAN_EVENT_LOOP; the committed default is the
 * select loop — coroutines OFF for HTTP workers, §13.4.3.) Without that env,
 * fiber mode fails fast with a 400 explaining the setup instead of silently
 * measuring a no-op.
 *
 * This endpoint exists ONLY to produce the measurements in
 * docs/adr/0002-coroutine-posture.md. It is kept (not removed) so the ADR
 * numbers are re-runnable; do not build product code on it.
 */
class BenchController
{
    private const UPSTREAM_URL = 'http://127.0.0.1:8899/upstream?ms=30';
    private const MAX_FANOUT = 32;
    private const MODES = ['blocking', 'fiber'];

    public function outbound(Request $request): Response
    {
        $mode = (string) $request->get('mode', '');
        $fanout = (int) $request->get('fanout', 8);

        if (!in_array($mode, self::MODES, true)) {
            return json(['error' => 'mode must be one of: blocking, fiber'])->withStatus(400);
        }
        if ($fanout < 1 || $fanout > self::MAX_FANOUT) {
            return json(['error' => 'fanout must be 1..' . self::MAX_FANOUT])->withStatus(400);
        }
        // Fiber mode is only measurable under the Fiber (Revolt) event loop —
        // fail fast with a 400 explaining the setup, never a silent no-op.
        if ($mode === 'fiber' && !Coroutine::isCoroutine()) {
            return json([
                'error' => 'fiber mode needs the Fiber event loop: WEBMAN_EVENT_LOOP="Workerman\\Events\\Fiber" php start.php start',
            ])->withStatus(400);
        }

        try {
            $startedAt = microtime(true);
            $completed = $mode === 'fiber'
                ? $this->fiberFanout($fanout)
                : $this->blockingFanout($fanout);
            $elapsedMs = (microtime(true) - $startedAt) * 1000;
        } catch (Throwable $throwable) {
            return json(['error' => $throwable->getMessage()])->withStatus(500);
        }

        return json([
            'mode' => $mode,
            'fanout' => $fanout,
            'requests' => $completed,
            'elapsed_ms' => round($elapsedMs, 2),
        ]);
    }

    private function blockingFanout(int $fanout): int
    {
        $client = new Client([
            'timeout' => 5,
            'connect_timeout' => 2,
            'http_errors' => false,
        ]);

        for ($i = 0; $i < $fanout; $i++) {
            $client->get(self::UPSTREAM_URL);
        }

        return $fanout;
    }

    private function fiberFanout(int $fanout): int
    {
        // Guaranteed by the 400 guard above; kept as an internal invariant.
        if (!Coroutine::isCoroutine()) {
            throw new RuntimeException('fiber mode requires a Fiber event loop');
        }

        $client = new AsyncClient([
            'timeout' => 5,
            'connect_timeout' => 2,
        ]);

        // Workerman\Coroutine\Parallel joins its fibers with Barrier, whose
        // Fiber driver DEFERS the waiter's resume to the next event-loop tick
        // (Barrier\Fiber via Timer::delay). That deferred resume is what makes
        // this fan-out deadlock-free at concurrency: the WaitGroup+Channel
        // join resumes the waiter SYNCHRONOUSLY from inside the last fiber's
        // done() call, which races with the http-client's socket callbacks
        // under the Fiber event loop and intermittently loses the resume
        // (measured P0.6.7; see docs/adr/0002-coroutine-posture.md).
        $parallel = new Parallel();
        $failures = 0;
        for ($i = 0; $i < $fanout; $i++) {
            $parallel->add(function () use ($client, &$failures): void {
                try {
                    $response = $client->request(self::UPSTREAM_URL);
                    if ($response->getStatusCode() !== 200) {
                        $failures++;
                    }
                } catch (Throwable) {
                    $failures++;
                }
            });
        }
        $parallel->wait();

        if ($failures > 0) {
            throw new RuntimeException($failures . ' upstream request(s) failed');
        }

        return $fanout;
    }
}