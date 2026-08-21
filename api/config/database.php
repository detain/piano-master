<?php
/**
 * MySQL configuration — webman/database (illuminate/database + Eloquent).
 *
 * Config shape discovered from the vendored plugin:
 *   vendor/webman/database/src/config/database.php
 *   vendor/webman/database/src/Initializer.php  (reads config('database'))
 *
 * Env overrides use getenv() ONLY (never the superglobal env/server arrays —
 * plan §13.4.2).
 * Parsed once at bootstrap; reload the workers after changing env.
 *
 * PDO::ATTR_TIMEOUT keeps the connect path bounded so /readyz fails fast
 * instead of hanging on an unreachable host (plan §13.6).
 */

return [
    'default' => 'mysql',
    'connections' => [
        'mysql' => [
            'driver' => 'mysql',
            'host' => getenv('DB_HOST') ?: '127.0.0.1',
            'port' => getenv('DB_PORT') ?: '3306',
            'database' => getenv('DB_DATABASE') ?: 'keyquest',
            'username' => getenv('DB_USERNAME') ?: 'root',
            'password' => getenv('DB_PASSWORD') ?: '',
            'charset' => 'utf8mb4',
            'collation' => 'utf8mb4_unicode_ci',
            'prefix' => '',
            'strict' => true,
            'engine' => null,
            'options' => [
                PDO::ATTR_EMULATE_PREPARES => false, // Required false for Swoole/Swow; also correct here.
                PDO::ATTR_TIMEOUT => 2,              // Connect timeout (seconds) — readiness must not hang.
            ],
            // Connection pool (used by Webman\Database\Manager in blocking mode too).
            'pool' => [
                'max_connections' => 5,
                'min_connections' => 0,
                'wait_timeout' => 3,
                'idle_timeout' => 60,
                'heartbeat_interval' => 50,
            ],
        ],
    ],
];