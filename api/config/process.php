<?php
/**
 * Webman custom processes (plan §13.4.1) — placeholder.
 *
 * Every entry spawns a long-lived worker beside the HTTP workers. The
 * cardinal rule: exactly ONE instance of anything that must not run twice.
 * Enforce with a Dragonfly lock (SET lock:<name> <token> NX PX 30000 +
 * refresh timer) so even a botched deploy that starts two copies degrades
 * to one active.
 *
 * Process inventory (planned):
 *  - webman (HTTP) 4 × vCPU      — REST API, stateless per request.
 *  - rtdn-consumer (1)           — pulls Google Pub/Sub RTDN notifications,
 *                                  writes job_outbox, drives the entitlement
 *                                  state machine (§15). Ordering matters.
 *  - queue-consumer (2–4)        — webman/redis-queue consumer: pipeline
 *                                  dispatch, CDN purge, email, receipt
 *                                  re-verification.
 *  - workout-gen (1)             — nightly per-profile 5-Min Workout
 *                                  generation (§9.3), sharded by
 *                                  profile-id modulo for future scale-out.
 *  - analytics-flush (1)         — drains the Dragonfly analytics buffer →
 *                                  batched MySQL/warehouse inserts every 5 s
 *                                  or 1 000 events.
 *  - scheduler (1)               — Workerman\Timer-based cron: streak
 *                                  rollover per timezone, entitlement expiry
 *                                  sweep, orphaned-download GC.
 *  - monitor (1)                 — webman/monitor: file-watch in dev; restarts
 *                                  any worker over the memory ceiling
 *                                  (> 256 MB RSS) in prod.
 */

return [
    // Example entry shape — uncomment and implement when each process lands:
    //
    // 'rtdn-consumer' => [
    //     'handler'   => \App\Process\RtdnConsumer::class,
    //     'count'     => 1,
    //     'constructor' => ['connection' => 'default'],
    //     'enable'    => true,
    //     'restart_interval' => 0,
    // ],
];