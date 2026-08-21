<?php

namespace App\Controller;

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
 */
class CacheController
{
    private const KEY = 'kq:boot_time';
    private const TTL_SECONDS = 60;

    public function now(Request $request): Response
    {
        $value = Redis::get(self::KEY);
        $fromCache = $value !== null;

        if (!$fromCache) {
            $value = date('c');
            Redis::set(self::KEY, $value, 'EX', self::TTL_SECONDS, 'NX');
            // A concurrent worker may have won the NX race; read back the survivor.
            $value = Redis::get(self::KEY) ?? $value;
        }

        return json([
            'value' => $value,
            'from_cache' => $fromCache,
            'ttl_seconds' => self::TTL_SECONDS,
        ]);
    }
}