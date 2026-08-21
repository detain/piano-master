<?php

declare(strict_types=1);

/**
 * Pinned Dragonfly command surface — plan §13.5.
 *
 * The single source of truth for every Redis command the KeyQuest API is
 * allowed to issue against Dragonfly. It is versioned with the pinned image
 * tag below; a Dragonfly version bump is a change that must re-pass
 * DragonflyCommandSurfaceTest.
 *
 * Contents
 *   meta        pinned Dragonfly version + provenance of the command set
 *   seed        setup commands run before the replay (each must also be
 *               error-free; DEL clears the kq:surf:* / {redis-queue}-surf-*
 *               test keys so replay is deterministic)
 *   commands    [name, [args...]] tuples replayed one-by-one via rawCommand;
 *               the test fails on ANY error reply
 *   fence       §13.5 fence probes that MUST fail gracefully (Dragonfly
 *               rejects them; asserting the rejection proves the fence)
 *   documented_skips   commands the stack will issue in production that are
 *               deliberately NOT replayed against a live shared instance,
 *               each with the reason
 *   findings    Redis commands verified UNSUPPORTED on this pinned Dragonfly
 *               during P0.6.4 — kept out of the app surface on purpose
 *
 * Blocking commands (BRPOP/BLPOP/BLMOVE) are pinned with a 1-second timeout
 * and seeded data so the replay never blocks the suite.
 *
 * The EVALSHA entry carries a placeholder sha; the test substitutes
 * sha1('return ARGV[1]') (Redis's script fingerprint) after SCRIPT LOAD so
 * the replay proves the EVALSHA path, not just EVAL.
 */

return [
    'meta' => [
        'pinned_dragonfly_version' => 'v1.29.0',
        'verified_on' => '2026-08-21',
        // Replay floor used by DragonflyCommandSurfaceTest: entries in
        // 'commands' must never drop below this count. Bump it when the
        // surface legitimately grows (it is the source of truth, not a magic
        // number in the test class).
        'pinned_command_entry_count' => 89,
        'provenance' => [
            'app' => 'app/Controller/CacheController.php (GET/SET EX NX), app/Controller/HealthController.php (PING), webman/redis connection setup (CONNECT/SELECT)',
            'queue' => 'vendor/webman/redis-queue/src/RedisConnection.php (PING heartbeat, ZADD/LPUSH), vendor/workerman/redis-queue/src/Client.php (LPUSH/ZADD/BRPOP/ZREVRANGEBYSCORE/ZREM/RPUSH, AUTH/SELECT at connect)',
            'plan' => 'plan_piano.md §13.5 relied-on list: strings, hashes, sorted sets, lists + BRPOP/BLMOVE, EXPIRE/TTL, MULTI/EXEC/WATCH, EVAL/EVALSHA, pub/sub, streams + consumer groups, SCAN, SELECT, ACL, REPLICAOF',
        ],
    ],

    'seed' => [
        ['DEL', [
            'kq:surf:str', 'kq:surf:ex', 'kq:surf:nx', 'kq:surf:setnx', 'kq:surf:setex',
            'kq:surf:psetex', 'kq:surf:ctr', 'kq:surf:float', 'kq:surf:list', 'kq:surf:list2',
            'kq:surf:empty-list', 'kq:surf:zset', 'kq:surf:hash', 'kq:surf:stream',
            'kq:surf:str2', 'kq:surf:m1', 'kq:surf:m2', 'kq:surf:renamed', 'kq:surf:mtx',
            'kq:surf:setex2', 'kq:surf:psetex2',
        ]],
        ['SET', ['kq:surf:str', 'v0']],
        ['SET', ['kq:surf:ex', 'v1', 'EX', '60']],
        ['SET', ['kq:surf:nx', 'v2', 'NX']],
        ['SETNX', ['kq:surf:setnx', 'v3']],
        ['SETEX', ['kq:surf:setex', '60', 'v4']],
        ['PSETEX', ['kq:surf:psetex', '60000', 'v5']],
        ['INCR', ['kq:surf:ctr']],
        ['LPUSH', ['kq:surf:list', 'a', 'b']],
        ['LPUSH', ['kq:surf:list2', 'c']],
        ['ZADD', ['kq:surf:zset', '10', 'm1', '20', 'm2']],
        ['HSET', ['kq:surf:hash', 'f1', 'v1', 'f2', 'v2']],
        ['XADD', ['kq:surf:stream', '*', 'field', 'value']],
    ],

    'commands' => [
        // --- connection / introspection ---
        ['PING', []],
        ['SELECT', ['0']],
        ['INFO', ['server']],
        ['DBSIZE', []],
        ['CLIENT', ['ID']],
        ['CLIENT', ['LIST']],
        ['CLIENT', ['SETNAME', 'kq-surf-test']],
        ['CONFIG', ['GET', 'maxmemory']],
        ['MEMORY', ['USAGE', 'kq:surf:str']],
        ['TYPE', ['kq:surf:str']],
        ['EXISTS', ['kq:surf:str']],
        ['SCAN', ['0', 'COUNT', '100']],
        ['ACL', ['LIST']],
        ['ACL', ['WHOAMI']],

        // --- strings (app + §13.5) ---
        ['SET', ['kq:surf:str', 'v0b']],
        ['GET', ['kq:surf:str']],
        ['GET', ['kq:surf:missing-key']],
        ['SET', ['kq:surf:str', 'v2', 'EX', '60']],
        ['SET', ['kq:surf:str2', 'v3', 'NX']],
        ['SETNX', ['kq:surf:setnx', 'v3b']],
        ['SETEX', ['kq:surf:setex2', '60', 'x']],
        ['PSETEX', ['kq:surf:psetex2', '60000', 'x']],
        ['GETSET', ['kq:surf:str', 'v4']],
        ['APPEND', ['kq:surf:str', '!']],
        ['STRLEN', ['kq:surf:str']],
        ['MGET', ['kq:surf:str', 'kq:surf:missing-key']],
        ['MSET', ['kq:surf:m1', 'a', 'kq:surf:m2', 'b']],
        ['INCR', ['kq:surf:ctr']],
        ['INCRBY', ['kq:surf:ctr', '2']],
        ['DECR', ['kq:surf:ctr']],
        ['INCRBYFLOAT', ['kq:surf:float', '1.5']],
        ['SETRANGE', ['kq:surf:str', '1', 'xx']],
        ['GETRANGE', ['kq:surf:str', '0', '3']],
        ['GETDEL', ['kq:surf:m1']],

        // --- keys / TTL ---
        ['EXPIRE', ['kq:surf:ex', '120']],
        ['TTL', ['kq:surf:ex']],
        ['PTTL', ['kq:surf:ex']],
        ['PERSIST', ['kq:surf:ex']],
        ['UNLINK', ['kq:surf:m2']],
        ['RENAME', ['kq:surf:setnx', 'kq:surf:renamed']],

        // --- hashes ---
        ['HSET', ['kq:surf:hash', 'f3', 'v3']],
        ['HGET', ['kq:surf:hash', 'f1']],
        ['HGETALL', ['kq:surf:hash']],
        ['HDEL', ['kq:surf:hash', 'f2']],
        ['HEXISTS', ['kq:surf:hash', 'f1']],
        ['HLEN', ['kq:surf:hash']],
        ['HINCRBY', ['kq:surf:hash', 'ctr', '2']],

        // --- lists (redis-queue producer/consumer paths) ---
        ['LPUSH', ['kq:surf:list', 'd']],
        ['RPUSH', ['kq:surf:list2', 'e']],
        ['LPOP', ['kq:surf:list']],
        ['RPOP', ['kq:surf:list2']],
        ['LLEN', ['kq:surf:list']],
        ['LRANGE', ['kq:surf:list', '0', '-1']],
        ['BLPOP', ['kq:surf:list', '1']],
        ['BRPOP', ['kq:surf:list2', '1']],
        ['BRPOP', ['kq:surf:empty-list', '1']],
        ['BLMOVE', ['kq:surf:list', 'kq:surf:list2', 'LEFT', 'RIGHT', '1']],

        // --- sorted sets (delayed queue) ---
        ['ZADD', ['kq:surf:zset', '30', 'm3']],
        ['ZRANGE', ['kq:surf:zset', '0', '-1']],
        ['ZRANGEBYSCORE', ['kq:surf:zset', '-inf', '+inf']],
        ['ZREVRANGEBYSCORE', ['kq:surf:zset', '+inf', '-inf', 'LIMIT', '0', '128']],
        ['ZSCORE', ['kq:surf:zset', 'm1']],
        ['ZCARD', ['kq:surf:zset']],
        ['ZINCRBY', ['kq:surf:zset', '1', 'm1']],
        ['ZREM', ['kq:surf:zset', 'm3']],

        // --- transactions ---
        ['MULTI', []],
        ['SET', ['kq:surf:mtx', '1']],
        ['EXEC', []],
        ['MULTI', []],
        ['DISCARD', []],
        ['WATCH', ['kq:surf:str']],
        ['UNWATCH', []],

        // --- scripting (token-bucket EVAL/EVALSHA) ---
        ['EVAL', ['return ARGV[1]', '0', 'lua-ok']],
        ['SCRIPT', ['LOAD', 'return ARGV[1]']],
        ['EVALSHA', ['__SHA1_RETURN_ARGV1__', '0', 'lua-sha-ok']],
        ['SCRIPT', ['EXISTS', '__SHA1_RETURN_ARGV1__']],

        // --- streams + consumer groups (analytics buffer) ---
        ['XADD', ['kq:surf:stream', '*', 'field2', 'value2']],
        ['XGROUP', ['CREATE', 'kq:surf:stream', 'grp', '0']],
        ['XREADGROUP', ['GROUP', 'grp', 'consumer1', 'COUNT', '10', 'STREAMS', 'kq:surf:stream', '>']],
        ['XACK', ['kq:surf:stream', 'grp', '0-0']],
        ['XINFO', ['STREAM', 'kq:surf:stream']],
        ['XINFO', ['GROUPS', 'kq:surf:stream']],
        ['XLEN', ['kq:surf:stream']],
        ['XTRIM', ['kq:surf:stream', 'MAXLEN', '10']],
        ['XREAD', ['COUNT', '5', 'STREAMS', 'kq:surf:stream', '0']],
        ['XAUTOCLAIM', ['kq:surf:stream', 'grp', 'consumer1', '0', '0-0']],

        // --- pub/sub (non-blocking introspection; SUBSCRIBE is a dedicated test) ---
        ['PUBLISH', ['kq:surf:chan', 'hello']],
        ['PUBSUB', ['CHANNELS']],
        ['PUBSUB', ['NUMSUB', 'kq:surf:chan']],
    ],

    'fence' => [
        // Redis Functions — §13.5 fence: unsupported, must FAIL.
        'function_load' => ['FUNCTION', ['LOAD', 'return 1']],
        'fcall' => ['FCALL', ['no_such_function', '0']],
        // CLIENT TRACKING — §13.5 fence: partial/no BCAST/PREFIX/REDIRECT.
        // Verified on v1.29.0: phpredis speaks RESP2 and Dragonfly rejects
        // every TRACKING form with a graceful ERR, so the fence holds
        // outright; the assertions prove it stays that way.
        'client_tracking_on' => ['CLIENT', ['TRACKING', 'ON']],
        'client_tracking_bcast' => ['CLIENT', ['TRACKING', 'ON', 'BCAST']],
        'client_tracking_prefix' => ['CLIENT', ['TRACKING', 'ON', 'PREFIX', 'kq:']],
        'client_tracking_redirect' => ['CLIENT', ['TRACKING', 'ON', 'REDIRECT', '0']],
        'client_tracking_off' => ['CLIENT', ['TRACKING', 'OFF']],
    ],

    'documented_skips' => [
        'REPLICAOF' => 'mutates the replication role; exercised only by the P3.9.4 failover drill, never against a live shared instance',
        'ACL SETUSER' => 'mutates ACLs and can lock out the app user; the read-only ACL LIST/WHOAMI probes are pinned instead',
        'FLUSHDB' => 'destructive; excluded from a live-instance surface probe',
        'FLUSHALL' => 'destructive; excluded from a live-instance surface probe',
        'KEYS' => 'O(N) scan anti-pattern; plan §13.5 relies on SCAN, which IS pinned',
        'SUBSCRIBE' => 'blocking; verified by the dedicated publish/subscribe round-trip test (testPublishSubscribeRoundTrip), not the replay loop',
        'AUTH' => 'app config ships with an empty password (config/redis.php); the AUTH path is covered at P2 when auth lands',
    ],

    'findings' => [
        'OBJECT ENCODING' => 'verified UNSUPPORTED on v1.29.0 (ERR unknown command `OBJECT`). Not in the app surface; do not call phpredis object() against Dragonfly.',
        'COPY' => 'verified UNSUPPORTED on v1.29.0 (ERR unknown command `COPY`). Not in the app surface; key duplication must use the app layer.',
    ],
];