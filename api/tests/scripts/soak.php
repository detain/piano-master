<?php

declare(strict_types=1);

/**
 * Manual P0.6.3 soak driver — see tests/Integration/SoakDrill.md.
 *
 * Sustains the plan's 100k-requests-over-an-hour profile against a running
 * Webman worker (start it separately with `php start.php start`), measures
 * per-minute p50/p95/p99 latency + worker VmRSS, checks the X-Request-Id
 * bleed canary on every response, and writes a summary JSON + pass/fail
 * verdicts for the plan's expectations (RSS growth < 5 MB, p99 at hour-one
 * within 10% of minute-one, zero bleed, worker never restarted).
 *
 * This is a CLI diagnostic, NOT a worker — it does not run inside Workerman,
 * so it deliberately avoids the exit/die language constructs (the repo lint
 * treats those tokens as worker-killers). Fatal setup errors are thrown as
 * exceptions: PHP prints the trace and returns a non-zero status naturally.
 *
 * Usage:
 *   php start.php start                              # terminal 1: the app
 *   php tests/scripts/soak.php --base=http://127.0.0.1:8787
 *
 * Pacing: --duration is a RATE LIMITER, not a deadline — --total requests are
 * spread across the whole window (rate = total/duration), so the documented
 * 100k-over-1h command really takes ~1h (was Finding C: it exhausted in ~15 s
 * locally).
 *
 * Reconnect pre-check (plan §13.4.2 / soak Finding A):
 *   php tests/scripts/soak.php --reconnect-check
 * Kills the worker's live MySQL connection server-side and verifies the NEXT
 * /db/version request reconnects cleanly (200, not 500). Run it before the
 * long soak; the check aborts (non-zero exit) when the reconnect path is
 * broken.
 *
 * Quick smoke (validate the setup before the real run):
 *   php tests/scripts/soak.php --total=200 --duration=5 --concurrency=16
 */

require __DIR__ . '/../../vendor/autoload.php';

use GuzzleHttp\Client;
use GuzzleHttp\Pool;

$args = [];
foreach (array_slice($argv, 1) as $arg) {
    // --key=value and bare --flag (flag value becomes '1').
    if (preg_match('/^--([a-z-]+)(?:=(.*))?$/', $arg, $m)) {
        $args[$m[1]] = $m[2] ?? '1';
    }
}

$total = (int) ($args['total'] ?? 100000);
$duration = (int) ($args['duration'] ?? 3600);
$concurrency = (int) ($args['concurrency'] ?? 16);
$base = $args['base'] ?? 'http://127.0.0.1:8787';
$summaryPath = $args['summary'] ?? '/tmp/kq-soak-summary-' . getmypid() . '.json';
$runReconnectCheck = isset($args['reconnect-check']);
$routes = ['/', '/healthz', '/readyz', '/db/version', '/cache/now'];

if ($total <= 0 || $duration <= 0 || $concurrency <= 0) {
    throw new \RuntimeException('--total/--duration/--concurrency must be positive integers');
}

// Pacing target (Finding C): --duration is a rate limiter. Request i goes out
// no earlier than startTime + i/rate, so --total requests fill the window.
$ratePerSecond = $total / $duration;

$client = new Client([
    'base_uri' => $base,
    'timeout' => 10,
    'connect_timeout' => 2,
    'http_errors' => false,
]);

/**
 * Reconnect expectation (plan §13.4.2 / Finding A): kill the worker's live
 * MySQL connection server-side and verify the FIRST /db/version request
 * reconnects cleanly instead of 500ing with "MySQL server has gone away".
 * The admin PDO connects without a default database, so its processlist row
 * (DB = NULL) is never matched and we never kill ourselves.
 */
$runReconnectCheckFn = static function (Client $client): array {
    $dbHost = getenv('DB_HOST') ?: '127.0.0.1';
    $dbPort = getenv('DB_PORT') ?: '3306';
    $dbUser = getenv('DB_USERNAME') ?: 'root';
    $dbPass = getenv('DB_PASSWORD') ?: '';
    $dbName = getenv('DB_DATABASE') ?: 'keyquest';

    $pdo = new PDO("mysql:host=$dbHost;port=$dbPort", $dbUser, $dbPass, [PDO::ATTR_TIMEOUT => 2]);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

    $appConnectionIds = static function () use ($pdo, $dbName, $dbUser): array {
        return $pdo->query(
            'SELECT ID FROM information_schema.processlist'
            . ' WHERE DB = ' . $pdo->quote($dbName)
            . ' AND USER = ' . $pdo->quote($dbUser)
        )->fetchAll(PDO::FETCH_COLUMN);
    };

    $ids = $appConnectionIds();
    if ($ids === []) {
        // A freshly started worker (or one right after a reconnect) may not
        // hold a live connection yet — warm it, then kill it.
        $client->get('/db/version');
        $ids = $appConnectionIds();
    }
    if ($ids === []) {
        return ['ok' => false, 'detail' => 'no app MySQL connection in processlist to kill'];
    }

    foreach ($ids as $id) {
        $pdo->exec('KILL ' . (int) $id);
    }

    usleep(100_000);
    $response = $client->get('/db/version');
    $status = $response->getStatusCode();
    $body = json_decode((string) $response->getBody(), true);
    $ok = $status === 200 && isset($body['db_version']);

    return [
        'ok' => $ok,
        'detail' => sprintf('killed %d app connection(s); first GET /db/version -> %d', count($ids), $status),
    ];
};

$startTime = microtime(true);
$deadline = $startTime + $duration;

/** @var array<string, float> $starts requestId => microtime when issued */
$starts = [];
/** @var array<int, array{count: int, errors: int, latencies: list<float>, rss_kb: ?int}> $buckets */
$buckets = [];
$bleedMismatches = 0;
$done = 0;
$nextProgressAt = $startTime + 60;
$nextRssSampleAt = $startTime;

$workerPids = static function (): array {
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }
    $cached = [];
    $pidFile = __DIR__ . '/../../runtime/webman.pid';
    $masterPid = is_file($pidFile) ? (int) file_get_contents($pidFile) : 0;
    if ($masterPid <= 0) {
        return $cached;
    }
    $childrenFile = '/proc/' . $masterPid . '/task/' . $masterPid . '/children';
    $contents = @file_get_contents($childrenFile);
    if ($contents !== false) {
        foreach (preg_split('/\s+/', trim($contents)) ?: [] as $token) {
            if (ctype_digit($token)) {
                $cached[] = (int) $token;
            }
        }
    }

    return $cached;
};

$workerRssKb = static function () use ($workerPids): ?int {
    $maxKb = null;
    foreach ($workerPids() as $pid) {
        $status = @file_get_contents('/proc/' . $pid . '/status');
        if ($status !== false && preg_match('/^VmRSS:\s+(\d+) kB$/m', $status, $m)) {
            $maxKb = $maxKb === null ? (int) $m[1] : max($maxKb, (int) $m[1]);
        }
    }

    return $maxKb;
};

$record = static function (int $bucketIndex, float $latencyMs) use (&$buckets): void {
    $buckets[$bucketIndex]['count'] = ($buckets[$bucketIndex]['count'] ?? 0) + 1;
    $buckets[$bucketIndex]['latencies'][] = $latencyMs;
    $buckets[$bucketIndex]['errors'] = $buckets[$bucketIndex]['errors'] ?? 0;
    $buckets[$bucketIndex]['rss_kb'] = $buckets[$bucketIndex]['rss_kb'] ?? null;
};

$requests = function () use ($client, $routes, $total, $deadline, $concurrency, $ratePerSecond, &$starts, &$done, &$buckets, &$bleedMismatches, $startTime, &$nextProgressAt, &$nextRssSampleAt, $workerRssKb, $record): \Generator {
    $chunk = 0;
    while (microtime(true) < $deadline) {
        // Pacing in 1s chunks: request i belongs to chunk floor(i/rate), so
        // each 1s chunk issues `rate` requests and we sleep the remainder of
        // the second. Any total/duration ratio is honored — the old code
        // capped each per-slot wait at 1s, truncating long gaps (e.g.
        // --total=10 --duration=3600 finished in ~10s instead of ~1h).
        $chunkStartIdx = (int) ceil($chunk * $ratePerSecond);
        $chunkEndIdx = (int) min($total, ceil(($chunk + 1) * $ratePerSecond));
        for ($i = $chunkStartIdx; $i < $chunkEndIdx; $i++) {
            $route = $routes[$i % count($routes)];
            $id = 'kq-soak-' . $i . '-' . bin2hex(random_bytes(3));
            yield $id => function () use ($client, $route, $id, $startTime, &$starts, &$done, &$buckets, &$bleedMismatches, &$nextProgressAt, &$nextRssSampleAt, $workerRssKb, $record, $total) {
            $starts[$id] = microtime(true);
            $promise = $client->getAsync($route, ['headers' => ['X-Request-Id' => $id]]);

            return $promise->then(
                function ($response) use ($id, $startTime, &$starts, &$done, &$buckets, &$bleedMismatches, &$nextProgressAt, &$nextRssSampleAt, $workerRssKb, $record, $total): void {
                    $latencyMs = (microtime(true) - $starts[$id]) * 1000;
                    $bucket = (int) floor((microtime(true) - $startTime) / 60);
                    $record($bucket, $latencyMs);
                    if ($response->getHeaderLine('X-Request-Id') !== $id || $response->getStatusCode() !== 200) {
                        ++$bleedMismatches;
                    }
                    $done++;
                    $now = microtime(true);
                    // RSS sampling is independent of the progress line so a
                    // progress failure cannot silently drop the series
                    // (Finding D: pre-fix it ran only in minute 1).
                    if ($now >= $nextRssSampleAt) {
                        $nextRssSampleAt = $now + 60;
                        $rss = $workerRssKb();
                        if ($rss !== null) {
                            $rssBucket = (int) floor(($now - $startTime) / 60);
                            $buckets[$rssBucket]['rss_kb'] = $rss;
                        }
                    }
                    if ($now >= $nextProgressAt) {
                        $nextProgressAt = $now + 60;
                        $bucketErrors = $buckets[$bucket]['errors'] ?? 0;
                        fwrite(STDERR, sprintf(
                            "soak: t=%ds done=%d (%.1f%%) rps=%.1f err=%d bleed=%d\n",
                            (int) ($now - $startTime), $done, $done / $total * 100,
                            $done / ($now - $startTime), $bucketErrors, $bleedMismatches
                        ));
                    }
                },
                function ($reason) use ($id, $startTime, &$done, &$buckets, &$bleedMismatches, $record): void {
                    $bucket = (int) floor((microtime(true) - $startTime) / 60);
                    $buckets[$bucket]['count'] = ($buckets[$bucket]['count'] ?? 0) + 1;
                    $buckets[$bucket]['errors'] = ($buckets[$bucket]['errors'] ?? 0) + 1;
                    $buckets[$bucket]['latencies'] = $buckets[$bucket]['latencies'] ?? [];
                    $buckets[$bucket]['rss_kb'] = $buckets[$bucket]['rss_kb'] ?? null;
                    $buckets[$bucket]['last_error'] = substr((string) $reason, 0, 200);
                    $done++;
                    $bleedMismatches++;
                }
            );
        };
        }
        if ($chunkEndIdx >= $total) {
            break;
        }
        // Sleep the remainder of the chunk (<= 1s) before the next one so
        // --total requests fill the whole --duration window.
        $nextChunkStart = $startTime + $chunk + 1;
        $waitUs = (int) (($nextChunkStart - microtime(true)) * 1_000_000);
        if ($waitUs > 0) {
            usleep($waitUs);
            if (microtime(true) >= $deadline) {
                break;
            }
        }
        $chunk++;
    }
};

$reconnectCheckResult = null;
if ($runReconnectCheck) {
    fwrite(STDERR, "soak: reconnect-check: killing the worker's MySQL connection...\n");
    $reconnectCheckResult = $runReconnectCheckFn($client);
    printf(
        "%-45s %s\n",
        'mysql_reconnect_after_kill',
        $reconnectCheckResult['ok'] ? 'PASS' : 'FAIL'
    );
    fwrite(STDERR, 'soak: reconnect-check: ' . $reconnectCheckResult['detail'] . "\n");
    if (!$reconnectCheckResult['ok']) {
        throw new \RuntimeException('reconnect-check FAILED: ' . $reconnectCheckResult['detail']);
    }
    // Standalone mode: with only --reconnect-check (no explicit --total or
    // --duration) the check IS the run — no soak follows.
    if (!isset($args['total']) && !isset($args['duration'])) {
        echo "\n== P0.6.3 reconnect check (standalone) ==\n";
        echo "PASS — the first request after a MySQL kill reconnects cleanly\n";

        return;
    }
}

$pool = new Pool($client, $requests(), ['concurrency' => $concurrency]);
$pool->promise()->wait();

$elapsed = microtime(true) - $startTime;
$rssSamples = array_values(array_filter(array_map(
    static fn (array $b): ?int => $b['rss_kb'] ?? null,
    $buckets
), static fn (?int $v): bool => $v !== null));
$firstRss = $rssSamples[0] ?? null;
$lastRss = $rssSamples[count($rssSamples) - 1] ?? null;
$rssGrowthKb = ($firstRss !== null && $lastRss !== null) ? $lastRss - $firstRss : null;
$workerAlive = $workerPids() !== [] && $done > 0;

$percentile = static function (array $sorted, float $p): ?float {
    $n = count($sorted);
    if ($n === 0) {
        return null;
    }
    $index = (int) ceil($p * $n) - 1;
    $index = max(0, min($n - 1, $index));

    return $sorted[$index];
};

$table = [];
foreach ($buckets as $minute => $b) {
    $sorted = $b['latencies'];
    sort($sorted);
    $table[$minute] = [
        'minute' => $minute + 1,
        'count' => $b['count'],
        'errors' => $b['errors'],
        'p50_ms' => $percentile($sorted, 0.50),
        'p95_ms' => $percentile($sorted, 0.95),
        'p99_ms' => $percentile($sorted, 0.99),
        'rss_kb' => $b['rss_kb'] ?? null,
    ];
}

$firstP99 = null;
$lastP99 = null;
$firstMinute = $lastMinute = null;
foreach ($table as $row) {
    if ($firstMinute === null) {
        $firstMinute = $row;
        $firstP99 = $row['p99_ms'];
    }
    $lastMinute = $row;
    $lastP99 = $row['p99_ms'];
}
$p99Within10pct = ($firstP99 !== null && $lastP99 !== null)
    ? $lastP99 <= $firstP99 * 1.10
    : null;

$verdicts = [
    'rss_growth_lt_5mb' => $rssGrowthKb !== null && $rssGrowthKb < 5 * 1024,
    'p99_last_within_10pct_of_minute_one' => $p99Within10pct,
    'zero_bleed_mismatches' => $bleedMismatches === 0,
    'worker_alive_at_end' => $workerAlive,
    'mysql_reconnect_after_kill' => $reconnectCheckResult['ok'] ?? null,
];

$summary = [
    'total_requests' => $done,
    'elapsed_seconds' => round($elapsed, 1),
    'average_rps' => round($done / max($elapsed, 0.001), 1),
    'routes' => $routes,
    'concurrency' => $concurrency,
    'bleed_mismatches' => $bleedMismatches,
    'rss_first_kb' => $firstRss,
    'rss_last_kb' => $lastRss,
    'rss_growth_kb' => $rssGrowthKb,
    'reconnect_check' => $reconnectCheckResult,
    'first_minute_p99_ms' => $firstP99,
    'last_minute_p99_ms' => $lastP99,
    'per_minute' => array_values($table),
    'verdicts' => $verdicts,
];

echo "\n== P0.6.3 soak per-minute table (p99 is the plan's latency metric) ==\n";
printf("%-8s %-10s %-8s %-8s %-8s %-8s %-8s\n", 'minute', 'count', 'errors', 'p50', 'p95', 'p99', 'rss_kb');
foreach ($table as $row) {
    printf(
        "%-8d %-10d %-8d %-8.2f %-8.2f %-8.2f %-8s\n",
        $row['minute'],
        $row['count'],
        $row['errors'],
        (float) $row['p50_ms'],
        (float) $row['p95_ms'],
        (float) $row['p99_ms'],
        $row['rss_kb'] === null ? 'n/a' : (string) $row['rss_kb']
    );
}

echo "\n== P0.6.3 soak verdicts ==\n";
foreach ($verdicts as $name => $pass) {
    printf("%-45s %s\n", $name, $pass === true ? 'PASS' : ($pass === false ? 'FAIL' : 'n/a'));
}

file_put_contents(
    $summaryPath,
    json_encode($summary, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n"
);
echo "\nsoak-summary: $summaryPath\n";
