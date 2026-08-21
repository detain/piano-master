<?php

declare(strict_types=1);

namespace support;

use Illuminate\Events\Dispatcher;
use Illuminate\Redis\Connections\Connection;
use ReflectionProperty;
use RuntimeException;
use Tests\Support\RedisCommandRecorder;
use Webman\Bootstrap;
use Webman\Coroutine\Pool;
use Webman\Redis\RedisManager;
use Workerman\Worker;

/**
 * Test-only Redis recording seam — plan §13.5 "can't silently grow".
 *
 * Registered in config/bootstrap.php but inert unless the integration suite
 * sets KQ_TEST_RECORD_REDIS=1 (see RedisCommandSurfaceGuardTest). When active
 * it wraps EVERY redis connection the app's pool creates in a
 * RedisCommandRecorder so the real request path (GET /cache/now → GET/SET,
 * /readyz → PING) is recorded, and optionally issues one redis-queue producer
 * round through the vendored client. The merged trace is flushed to
 * KQ_TEST_REDIS_TRACE_FILE when the worker stops, where the parent test reads
 * it and asserts it stays a subset of the pinned Dragonfly surface.
 *
 * State-hygiene note: this is bootstrap-time test instrumentation, not
 * request-scoped data — the trace is process-global on purpose. It is gated by
 * env vars that only the integration suite sets; production boots hit the
 * early return and nothing here runs.
 *
 * Why the pool creator is swapped (not the facade): webman/redis pools
 * connections lazily and hands each request whatever connection is free, so a
 * single wrapped connection would miss traffic. Replacing the pool's creator
 * (Webman\Coroutine\Pool::setConnectionCreator, public) guarantees every
 * connection ever made is recorder-wrapped. The one reflection below injects
 * that pool into Webman\Redis\RedisManager::$pools (protected static) before
 * the first connection() call.
 */
final class RedisRecordBootstrap implements Bootstrap
{
    public static function start(?Worker $worker): void
    {
        if (!getenv('KQ_TEST_RECORD_REDIS')) {
            return;
        }

        require_once base_path() . '/tests/Support/RedisCommandRecorder.php';

        $traceFile = (string) getenv('KQ_TEST_REDIS_TRACE_FILE');
        if ($traceFile === '') {
            throw new RuntimeException('KQ_TEST_RECORD_REDIS=1 requires KQ_TEST_REDIS_TRACE_FILE');
        }

        /** @var list<RedisCommandRecorder> $recordings */
        $recordings = [];

        self::wrapRedisPool($recordings);
        if (getenv('KQ_TEST_REDIS_QUEUE_PRODUCE')) {
            self::produceQueueJob($recordings);
        }

        $flush = static function () use (&$recordings, $traceFile): void {
            $trace = [];
            foreach ($recordings as $recorder) {
                foreach ($recorder->trace() as $entry) {
                    $trace[] = $entry;
                }
            }
            $names = [];
            foreach ($trace as $entry) {
                if (!in_array($entry['command'], $names, true)) {
                    $names[] = $entry['command'];
                }
            }
            file_put_contents(
                $traceFile,
                json_encode([
                    'command_names' => $names,
                    'trace' => $trace,
                    'recorder_count' => count($recordings),
                ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n"
            );
        };

        if ($worker) {
            // Runs before the worker process exits on SIGTERM/SIGINT, so the
            // harness's shutdown() (which waits for child exit) sees the trace.
            $worker->onWorkerStop = $flush;
        }
        // Belt and suspenders: Workerman children terminate via the exit
        // language construct after stop, which also runs shutdown functions;
        // covers non-worker processes too.
        register_shutdown_function($flush);
    }

    /**
     * Replace the webman/redis pool creator so every connection the app makes
     * is wrapped in a recorder whose trace lands in $recordings.
     *
     * @param list<RedisCommandRecorder> $recordings
     */
    private static function wrapRedisPool(array &$recordings): void
    {
        $manager = \support\Redis::instance();
        $redisConfig = config('redis');
        $name = 'default';
        $poolConfig = $redisConfig[$name]['pool'] ?? [];
        $pool = new Pool($poolConfig['max_connections'] ?? 10, $poolConfig);

        $pool->setConnectionCreator(static function () use ($manager, $name, &$recordings): Connection {
            $connection = $manager->resolve($name);
            $connection->setName($name);
            if (class_exists(Dispatcher::class)) {
                $connection->setEventDispatcher(new Dispatcher());
            }

            $recorder = new RedisCommandRecorder($connection->client());
            $recordings[] = $recorder;

            // Connection::$client is protected; swap in the recorder so every
            // command dispatched by PhpRedisConnection flows through it.
            $clientProperty = new ReflectionProperty($connection, 'client');
            $clientProperty->setValue($connection, $recorder);

            return $connection;
        });
        $pool->setConnectionCloser(static fn ($connection) => $connection->client()->close());
        $pool->setHeartbeatChecker(static fn ($connection) => $connection->ping());

        $poolsProperty = new ReflectionProperty(RedisManager::class, 'pools');
        $pools = $poolsProperty->getValue();
        $pools[$name] = $pool;
        $poolsProperty->setValue(null, $pools);
    }

    /**
     * One redis-queue producer round through the vendored client
     * (Webman\RedisQueue\RedisConnection, a phpredis \Redis subclass) so the
     * delayed-zset + waiting-list wire commands are recorded too. The pushed
     * package mirrors RedisConnection::send(); we call the wire commands
     * directly so the recorder sees the ACTUAL Redis command (LPUSH/ZADD)
     * rather than the PHP-level send() wrapper.
     *
     * @param list<RedisCommandRecorder> $recordings
     */
    private static function produceQueueJob(array &$recordings): void
    {
        $appConfig = config('redis')['default'];
        $queueConnection = new \Webman\RedisQueue\RedisConnection();
        $queueConnection->connectWithConfig([
            'host' => $appConfig['host'],
            'port' => (int) $appConfig['port'],
            'db' => (int) $appConfig['database'],
            'auth' => (string) ($appConfig['password'] ?? ''),
            'timeout' => (float) ($appConfig['timeout'] ?? 2),
            'ping' => 55,
            'prefix' => '',
        ]);

        $recorder = new RedisCommandRecorder($queueConnection);
        $recordings[] = $recorder;

        $queue = 'kq:test:queue';
        $package = json_encode([
            'id' => 'kq-guard-' . bin2hex(random_bytes(4)),
            'time' => time(),
            'delay' => 0,
            'attempts' => 0,
            'queue' => $queue,
            'data' => ['source' => 'redis-command-surface-guard'],
        ], JSON_UNESCAPED_SLASHES);

        // RedisConnection::send($queue, $data, $delay) issues exactly these:
        $recorder->lPush('{redis-queue}-waiting' . $queue, $package);
        $recorder->zAdd('{redis-queue}-delayed', time() + 60, $package);

        // Leave no residue: remove exactly what we added, through the
        // UNwrapped connection so cleanup is invisible to the trace. The
        // waiting key is our own queue; the delayed zset is shared, so only
        // ZREM our package from it — never DEL the whole key.
        $queueConnection->del('{redis-queue}-waiting' . $queue);
        $queueConnection->zrem('{redis-queue}-delayed', $package);
    }
}