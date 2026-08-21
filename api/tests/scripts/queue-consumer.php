<?php

declare(strict_types=1);

/**
 * Manual P0.6.4 drill consumer — see tests/Integration/SoakDrill.md.
 *
 * A minimal redis-queue consumer built on the vendored webman/redis-queue
 * RedisConnection (a phpredis \Redis subclass with the plugin's own
 * reconnect-on-connection-loss logic in execCommand()). It consumes one
 * waiting queue via BRPOP and promotes due delayed-zset entries back to the
 * waiting list (the delayed-sweep shape of workerman/redis-queue's
 * tryToPullDelayQueue). This is the consume path the P0.6.4 drill restarts
 * Dragonfly under.
 *
 * Observation lines are printed to stdout: consumed packages, delayed
 * promotions, connection drops and reconnects. The drill record should copy
 * these lines verbatim.
 *
 * This is a CLI diagnostic, NOT a worker — it deliberately avoids the
 * exit/die language constructs (the repo lint treats those tokens as
 * worker-killers). Fatal errors are thrown: PHP prints the trace and returns
 * a non-zero status naturally.
 *
 * Usage:
 *   php tests/scripts/queue-consumer.php --queue=kq:drill:queue
 */

require __DIR__ . '/../../vendor/autoload.php';

use Webman\RedisQueue\RedisConnection;

$args = [];
foreach (array_slice($argv, 1) as $arg) {
    if (preg_match('/^--([a-z-]+)=(.*)$/', $arg, $m)) {
        $args[$m[1]] = $m[2];
    }
}

$queue = $args['queue'] ?? 'kq:drill:queue';
$host = getenv('REDIS_HOST') ?: '127.0.0.1';
$port = (int) (getenv('REDIS_PORT') ?: 6379);
$db = (int) ($args['db'] ?? 0);
$failEvery = (int) ($args['fail-every'] ?? 0); // re-queue every Nth package (retry-path demo)

$waitingKey = '{redis-queue}-waiting' . $queue;
$delayedKey = '{redis-queue}-delayed';

$connect = static function () use ($host, $port, $db): RedisConnection {
    $connection = new RedisConnection();
    $connection->connectWithConfig([
        'host' => $host,
        'port' => $port,
        'db' => $db,
        'auth' => '',
        'timeout' => 2,
        'ping' => 55,
        'prefix' => '',
    ]);

    return $connection;
};

/** @var RedisConnection $redis */
$redis = $connect();
echo "[consumer] connected to $host:$port, queue=$queue, waiting=$waitingKey, delayed=$delayedKey\n";

$processed = 0;
$isConnectionLoss = static function (\Throwable $e): bool {
    $msg = strtolower($e->getMessage());

    return str_contains($msg, 'connection lost') || str_contains($msg, 'went away');
};

while (true) {
    // Delayed sweep: promote due delayed entries back to the waiting list
    // (same shape as workerman/redis-queue's tryToPullDelayQueue timer).
    try {
        $due = $redis->zrevrangebyscore($delayedKey, (string) time(), '-inf', ['limit' => [0, 128]]);
        if (is_array($due) && $due !== []) {
            foreach ($due as $package) {
                if ($redis->zrem($delayedKey, $package) === 1) {
                    $redis->lpush($waitingKey, $package);
                    echo "[delayed] promoted " . substr((string) $package, 0, 120) . "\n";
                }
            }
        }
    } catch (\Throwable $e) {
        echo "[delayed] sweep error: {$e->getMessage()}\n";
        if ($isConnectionLoss($e)) {
            $redis = $connect();
            echo "[delayed] reconnected after connection loss\n";
        }
    }

    try {
        $popped = $redis->brpop($waitingKey, 5);
    } catch (\Throwable $e) {
        if ($isConnectionLoss($e)) {
            echo "[consumer] connection lost: {$e->getMessage()}\n";
            $redis = $connect();
            echo "[consumer] reconnected\n";
            continue;
        }
        echo "[consumer] error: {$e->getMessage()}\n";
        sleep(1);
        continue;
    }

    if ($popped === null || $popped === false) {
        continue; // BRPOP timeout — loop again
    }

    $package = is_array($popped) ? ($popped[1] ?? '') : '';
    ++$processed;
    echo "[consumer] consumed ($processed): " . substr((string) $package, 0, 200) . "\n";

    if ($failEvery > 0 && $processed % $failEvery === 0) {
        // Simulate a consumer failure: re-queue into the delayed zset so the
        // retry/delayed path is exercised (workerman/redis-queue retry()).
        $retryAt = time() + 2;
        $redis->zadd($delayedKey, $retryAt, (string) $package);
        echo "[retry] failed package re-queued to delayed (retry at $retryAt)\n";
    }

    sleep(1); // simulate work; keeps the loop observable
}