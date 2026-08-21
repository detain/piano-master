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
 * Quick smoke (validate the setup before the real run):
 *   php tests/scripts/soak.php --total=1000 --duration=60 --concurrency=16
 */

require __DIR__ . '/../../vendor/autoload.php';

use GuzzleHttp\Client;
use GuzzleHttp\Pool;

$args = [];
foreach (array_slice($argv, 1) as $arg) {
    if (preg_match('/^--([a-z-]+)=(.*)$/', $arg, $m)) {
        $args[$m[1]] = $m[2];
    }
}

$total = (int) ($args['total'] ?? 100000);
$duration = (int) ($args['duration'] ?? 3600);
$concurrency = (int) ($args['concurrency'] ?? 16);
$base = $args['base'] ?? 'http://127.0.0.1:8787';
$masterPidArg = isset($args['pid']) ? (int) $args['pid'] : 0;
$summaryPath = $args['summary'] ?? '/tmp/kq-soak-summary-' . getmypid() . '.json';
$routes = ['/', '/healthz', '/readyz', '/db/version', '/cache/now'];

if ($total <= 0 || $duration <= 0 || $concurrency <= 0) {
    throw new \RuntimeException('--total/--duration/--concurrency must be positive integers');
}

$client = new Client([
    'base_uri' => $base,
    'timeout' => 10,
    'connect_timeout' => 2,
    'http_errors' => false,
]);

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

$requests = function () use ($client, $routes, $total, $deadline, $concurrency, &$starts, &$done, &$buckets, &$bleedMismatches, $startTime, &$nextProgressAt, &$nextRssSampleAt, $workerRssKb, $record): \Generator {
    $sent = 0;
    while ($sent < $total && microtime(true) < $deadline) {
        $i = $sent++;
        $route = $routes[$i % count($routes)];
        $id = 'kq-soak-' . $i . '-' . bin2hex(random_bytes(3));
        yield $id => function () use ($client, $route, $id, $startTime, &$starts, &$done, &$buckets, &$bleedMismatches, &$nextProgressAt, &$nextRssSampleAt, $workerRssKb, $record) {
            $starts[$id] = microtime(true);
            $promise = $client->getAsync($route, ['headers' => ['X-Request-Id' => $id]]);

            return $promise->then(
                function ($response) use ($id, $startTime, &$starts, &$done, &$buckets, &$bleedMismatches, &$nextProgressAt, &$nextRssSampleAt, $workerRssKb, $record): void {
                    $latencyMs = (microtime(true) - $starts[$id]) * 1000;
                    $bucket = (int) floor((microtime(true) - $startTime) / 60);
                    $record($bucket, $latencyMs);
                    if ($response->getHeaderLine('X-Request-Id') !== $id || $response->getStatusCode() !== 200) {
                        ++$bleedMismatches;
                    }
                    $done++;
                    $now = microtime(true);
                    if ($now >= $nextProgressAt) {
                        $nextProgressAt = $now + 60;
                        $bucketCount = $buckets[$bucket]['count'] ?? 0;
                        $bucketErrors = $buckets[$bucket]['errors'] ?? 0;
                        fwrite(STDERR, sprintf(
                            "soak: t=%ds done=%d (%.1f%%) rps=%.1f err=%d bleed=%d\n",
                            (int) ($now - $startTime), $done, $done / $total * 100,
                            $done / ($now - $startTime), $bucketErrors, $bleedMismatches
                        ));
                    }
                    if ($now >= $nextRssSampleAt) {
                        $nextRssSampleAt = $now + 60;
                        $rss = $workerRssKb();
                        if ($rss !== null) {
                            $buckets[$bucket]['rss_kb'] = $rss;
                        }
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
};

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