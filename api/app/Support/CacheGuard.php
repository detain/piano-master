<?php

declare(strict_types=1);

namespace App\Support;

use Throwable;

/**
 * CacheGuard — graceful degradation for cache (Dragonfly) outages (plan
 * §13.6, P0.6.6 failover drill).
 *
 * A cache hit is a performance optimization, not a correctness requirement:
 * when Dragonfly is unreachable the cache endpoints must NOT 500. This guard
 * runs a Redis callable and, on any Throwable, logs ONE structured warning
 * per WARN_THROTTLE_SECONDS and returns the UNAVAILABLE sentinel so the
 * caller degrades (falls back to a direct computation and marks the response
 * `degraded: true`).
 *
 * State-hygiene (§13.4.2): the warn throttle is a process-static timestamp —
 * PROCESS state, never request data. The only thing this static holds is
 * "when did this worker last print a cache warning", which is exactly the
 * class of legitimate process state the rule allows. It is whitelisted in
 * StateBleedGuardTest::STATIC_WHITELIST with this reason on file.
 */
final class CacheGuard
{
    public const UNAVAILABLE = '__cache_unavailable__';

    private const WARN_THROTTLE_SECONDS = 5;

    /** Process state: last warning emission time (float microtime), not request data. */
    private static float $lastWarnedAt = 0.0;

    private function __construct()
    {
    }

    /**
     * Run $redisCall, converting any Throwable into UNAVAILABLE plus a
     * throttled structured warning on STDOUT (the middleware log channel).
     */
    public static function guarded(callable $redisCall): mixed
    {
        try {
            return $redisCall();
        } catch (Throwable $throwable) {
            self::warnOnce($throwable);

            return self::UNAVAILABLE;
        }
    }

    private static function warnOnce(Throwable $throwable): void
    {
        $now = microtime(true);
        if ($now - self::$lastWarnedAt < self::WARN_THROTTLE_SECONDS) {
            return;
        }
        self::$lastWarnedAt = $now;

        $line = json_encode(
            [
                'ts' => date('c'),
                'event' => 'cache_guard.degraded',
                'message' => $throwable->getMessage(),
            ],
            JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE
        );
        fwrite(STDOUT, $line . "\n");
    }
}