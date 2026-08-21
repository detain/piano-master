<?php

namespace App\Controller;

use support\Db;
use support\Redis;
use support\Request;
use support\Response;
use Throwable;

/**
 * Health endpoints (plan §13.6).
 *
 * /healthz = liveness: process is up, no dependencies touched.
 * /readyz  = readiness: MySQL + Dragonfly ping, fails fast with 503.
 */
class HealthController
{
    public function healthz(Request $request): Response
    {
        return json(['status' => 'ok']);
    }

    public function readyz(Request $request): Response
    {
        $mysqlUp = $this->mysqlReachable();
        $dragonflyUp = $this->dragonflyReachable();

        $payload = [
            'status' => $mysqlUp && $dragonflyUp ? 'ready' : 'not_ready',
            'mysql' => $mysqlUp,
            'dragonfly' => $dragonflyUp,
        ];

        return json($payload)->withStatus($mysqlUp && $dragonflyUp ? 200 : 503);
    }

    /**
     * Ping MySQL through the app's configured connection (short timeouts in
     * config/database.php keep this bounded).
     */
    private function mysqlReachable(): bool
    {
        try {
            Db::select('SELECT 1');
            return true;
        } catch (Throwable) {
            return false;
        }
    }

    /**
     * Ping Dragonfly through the app's configured connection (short timeouts
     * in config/redis.php keep this bounded). phpredis ping() returns true
     * or '+PONG' on success.
     */
    private function dragonflyReachable(): bool
    {
        try {
            return (bool) Redis::ping();
        } catch (Throwable) {
            return false;
        }
    }
}