<?php
/**
 * Dragonfly (Redis-wire-compatible) configuration — webman/redis.
 *
 * Config shape discovered from the vendored plugin:
 *   vendor/webman/redis/src/support/Redis.php      (reads config('redis'))
 *   vendor/illuminate/redis/RedisManager.php       ('default' connection + 'options')
 *   vendor/illuminate/redis/Connectors/PhpRedisConnector.php (maps timeout/read_timeout)
 *
 * The client is phpredis ('redis' ext is installed locally, toolchain.md).
 * 'timeout' / 'read_timeout' bound the connect/read path so /readyz fails
 * fast instead of hanging (plan §13.6). Per-connection 'pool' config is read
 * by Webman\Redis\RedisManager.
 *
 * Env overrides use getenv() ONLY — never the superglobal env/server arrays
 * (plan §13.4.2).
 * Parsed once at bootstrap; reload the workers after changing env.
 *
 * Dragonfly fence (plan §13.5): this app only issues commands on the
 * verified-supported surface — GET/SET/SETEX/INCR/EXPIRE/TTL etc.
 */

return [
    'client' => getenv('REDIS_CLIENT') ?: 'phpredis',
    'default' => [
        'host' => getenv('REDIS_HOST') ?: '127.0.0.1',
        'port' => (int) (getenv('REDIS_PORT') ?: 6379),
        'password' => getenv('REDIS_PASSWORD') ?: '',
        'database' => (int) (getenv('REDIS_DATABASE') ?: 0),
        'timeout' => 2.0,
        'read_timeout' => 2.0,
        'persistent' => false,
        'prefix' => '',
    ],
    'options' => [],
    'pool' => [
        'max_connections' => 10,
        'min_connections' => 0,
        'wait_timeout' => 3,
        'idle_timeout' => 60,
        'heartbeat_interval' => 50,
    ],
];