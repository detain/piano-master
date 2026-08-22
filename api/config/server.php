<?php
/**
 * HTTP server configuration.
 *
 * Webman reads every key here once at bootstrap. All keys are defined so the
 * long-lived worker boots without undefined-array-key warnings under PHP 8.
 *
 * Plan §13.6: nginx terminates TLS and reverse-proxies to 127.0.0.1:8787.
 * Default `count` is 1 worker for the spike (deterministic logs, matches the
 * P0.6.3 longevity soak); scale to 4 x vCPU at deploy time via WEBMAN_COUNT
 * (e.g. `WEBMAN_COUNT=4 php start.php start`).
 *
 * `event_loop` defaults to empty (Workerman picks its select-based loop; the
 * state-hygiene-safe posture for HTTP workers, §13.4.3). The Fiber/Revolt
 * loop is opt-in via WEBMAN_EVENT_LOOP=Workerman\Events\Fiber for
 * coroutine-mode runs (the P0.6.7 /bench/outbound spike, the queue consumer
 * fan-out posture) — it is NOT the HTTP-worker default.
 */

return [
    // Override the whole listen string via env, e.g. WEBMAN_LISTEN=http://127.0.0.1:18787
    'listen' => getenv('WEBMAN_LISTEN') ?: 'http://0.0.0.0:8787',
    'transport' => 'tcp',
    'context' => [],
    'name' => 'keyquest-api',
    'count' => (int) (getenv('WEBMAN_COUNT') ?: 1),
    'user' => getenv('WEBMAN_USER') ?: '',
    'group' => getenv('WEBMAN_GROUP') ?: '',
    'reusePort' => false,
    'event_loop' => getenv('WEBMAN_EVENT_LOOP') ?: '',
    'stop_timeout' => 2,
    'pid_file' => runtime_path('webman.pid'),
    'status_file' => runtime_path('webman.status'),
    // stdout_file stays empty so boot logs and the middleware request log
    // lines (plan §13.6) land on the terminal in foreground mode; Workerman's
    // own status log goes into runtime/ (git-ignored).
    'stdout_file' => '',
    'log_file' => runtime_path('logs/workerman.log'),
    'max_package_size' => 10 * 1024 * 1024,
];