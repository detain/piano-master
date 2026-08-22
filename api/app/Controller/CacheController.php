<?php

namespace App\Controller;

use App\Support\CacheGuard;
use support\Redis;
use support\Request;
use support\Response;

/**
 * GET /cache/now — Dragonfly read-through cache (plan §20 P0.6.1).
 *
 * Reads the key kq:boot_time; on a miss, sets it with SET NX EX 60 (the
 * canonical read-through pattern — SETNX-ish, atomic, no lost update if two
 * workers race) and returns the value that won.
 *
 * Uses only the Dragonfly-supported command surface (GET/SET/EXPIRE — §13.5).
 *
 * P0.6.6: when Dragonfly is unreachable, CacheGuard converts the Redis
 * failure into a degraded-but-successful 200 (value falls back to the local
 * clock, `degraded: true` is set) instead of a 500 — a cache outage must not
 * take down read endpoints.
 */
class CacheController
{
    private const KEY = 'kq:boot_time';
    private const TTL_SECONDS = 60;

    public function now(Request $request): Response
    {
        $cached = CacheGuard::guarded(fn () => $this->readThrough());

        if ($cached === CacheGuard::UNAVAILABLE) {
            return json([
                'value' => date('c'),
                'from_cache' => false,
                'ttl_seconds' => self::TTL_SECONDS,
                'degraded' => true,
            ]);
        }

        return json([
            'value' => $cached['value'],
            'from_cache' => $cached['from_cache'],
            'ttl_seconds' => self::TTL_SECONDS,
        ]);
    }

    /**
     * @return array{value: string, from_cache: bool}
     */
    private function readThrough(): array
    {
        $value = Redis::get(self::KEY);
        $fromCache = $value !== null;

        if (!$fromCache) {
            Redis::set(self::KEY, date('c'), 'EX', self::TTL_SECONDS, 'NX');
            // A concurrent worker may have won the NX race; read back the
            // survivor. If the read-back itself fails, keep the local value.
            $value = Redis::get(self::KEY) ?? date('c');
            $fromCache = true;
        }

        return ['value' => (string) $value, 'from_cache' => $fromCache];
    }
}