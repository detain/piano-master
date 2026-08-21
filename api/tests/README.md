# Backend tests

Strategy from plan §13.7 — no Laravel-style test harness; a layered suite that
catches the Webman-specific failure modes nothing else will.

## Layers

1. **Unit (majority)** — `tests/unit/`: pure services with plain PHPUnit, no
   framework boot: entitlement state machine, sync merge (last-write-wins +
   max(stars)), workout generator, rate-limit math. Run with `composer test`.

2. **Integration** — `tests/Integration/`: `WebmanTestHarness` starts a real
   Webman instance on an ephemeral port in a child process (`WEBMAN_LISTEN`
   env override, `proc_open`, `/readyz` wait); tests hit it over HTTP with
   Guzzle. Requires MySQL and Dragonfly up (`docker compose up -d` in api/);
   every class SKIPS (never fails) when the services are unreachable so CI
   without containers stays green. `SkeletonRoutesTest` covers the P0.6.1
   route surface; `WorkerLongevityTest` is the §13.7 do-not-skip suite;
   `RedisCommandSurfaceGuardTest` proves the app's real Redis usage stays
   inside the pinned Dragonfly surface.

3. **Worker-longevity suite (do not skip)** — `tests/Integration/
   WorkerLongevityTest.php` boots ONE worker and fires 10 000 requests through
   it (Guzzle concurrency 8) across mixed routes, then asserts (a) RSS growth
   < 20 MB after warmup — plan target is 5 MB, the measured delta is reported
   in a per-run tempnam summary (path printed to stderr as
   `longevity-summary-path:`), (b) zero cross-request data bleed
   (each request sends a unique `X-Request-Id`; the response MUST echo the
   same id back — a mismatch means request N's state leaked into request
   N+1), (c) the worker is still alive. This is the test that catches §13.4.2
   state-hygiene violations, and nothing else will. Tagged `@group longevity`
   AND in the default suite — run it alone with:

   ```
   cd api && vendor/bin/phpunit --configuration phpunit.xml.dist --group longevity
   ```

4. **State-bleed guard (static)** — `tests/Integration/StateBleedGuardTest.php`
   greps api/app + api/config + api/support for the three canonical §13.4.2
   bug classes: `exit`/`die` calls outside start.php, superglobal reads, and
   mutable `static $` state in app/ (whitelist in the class docblock). Each
   check maps to a §13.4.2 rule with the failure mode it catches. This is the
   P0.6.2 "torture test" kept as a permanent gate — the greps flag the
   canonical bugs by construction.

5. **Dragonfly command-surface test** — `tests/Integration/
   DragonflyCommandSurfaceTest.php` replays the ENTIRE pinned command list
   from `tests/Support/dragonfly_command_surface.php` (the commands the app
   issues: GET/SET/SETNX/EXPIRE/TTL/INCR/PING…, plus every command
   webman/redis-queue issues: LPUSH/ZADD/BRPOP/ZREVRANGEBYSCORE/ZREM/RPUSH…,
   plus the plan §13.5 relied-on surface: hashes, sorted sets, streams,
   MULTI/EXEC/WATCH, EVAL/EVALSHA, SCAN, ACL, pub/sub) against the live
   Dragonfly container and fails on any error reply, so the verified surface
   can't silently grow. It also probes the §13.5 fence: `FUNCTION LOAD`/
   `FCALL` and `CLIENT TRACKING` (ON/BCAST/PREFIX/REDIRECT/OFF) MUST fail
   gracefully. Command count + fence rejections land in a per-run tempnam
   summary (path printed to stderr as `dragonfly-surface-summary-path:`).

6. **Redis command-surface guard (app path)** — `tests/Integration/
   RedisCommandSurfaceGuardTest.php` wires the recorder INTO the app (the
   §13.5 "can't silently grow" enforcement point the surface replay alone
   cannot provide): it boots the real Webman child with the
   `RedisRecordBootstrap` seam (registered in `config/bootstrap.php`, inert
   unless `KQ_TEST_RECORD_REDIS` is set), which wraps every pooled redis
   connection in a `RedisCommandRecorder` and issues one redis-queue producer
   round through the vendored client. The test then exercises `GET /cache/now`
   (miss + hit), shuts the child down, and asserts the recorded commands
   (GET/SET/PING/LPUSH/ZADD) are a SUBSET of the pinned surface. Any future
   route that calls an unpinned command fails here loudly.

6. **RTDN simulator** — replays recorded Google Play Pub/Sub payloads for
   every subscription transition, including out-of-order and duplicate
   delivery, against `POST /webhooks/play-rtdn`.

7. **Load** — k6 scenarios per route class, run nightly against staging, with
   the §13.6 capacity numbers (≥ 5 000 req/s cached reads, ≥ 800 req/s
   authenticated progress writes, p99 < 120 ms) as the pass threshold.

## Running the suites

```bash
# Full suite (unit + integration; integration needs docker up)
cd api && docker compose up -d
cd api && vendor/bin/phpunit --configuration phpunit.xml.dist

# Longevity soak alone (same test, tagged for CI)
cd api && vendor/bin/phpunit --configuration phpunit.xml.dist --group longevity

# Integration only
cd api && vendor/bin/phpunit --configuration phpunit.xml.dist --testsuite integration
```

All integration classes SKIP when MySQL/Dragonfly are unreachable, so the
suite is safe to run without the container stack — it just won't exercise
the live paths.

## RedisCommandRecorder (`tests/Support/RedisCommandRecorder.php`)

A decorator around phpredis that records every command name + args before
delegating (plan §13.5's "can't silently grow" primitive). Wire it around any
Redis connection the app uses in a test:

```php
require_once __DIR__ . '/../Support/RedisCommandRecorder.php';

$recorder = new RedisCommandRecorder(new Redis());
$recorder->connect('127.0.0.1', 6379, 2);
$recorder->get('kq:key');                     // recorded: GET kq:key
$recorder->rawCommand('SET', 'kq:key', 'v');  // recorded: SET kq:key v
$recorder->commands();                        // ['GET', 'SET']
$recorder->assertOnlyVerifiedCommands(
    ['GET', 'SET', 'EXPIRE', 'TTL', 'INCR'],
    'Dragonfly surface grew'
);                                            // throws if a command is missing
```

The pinned list the recorder guards against lives in
`tests/Support/dragonfly_command_surface.php` — the single source of truth
for what the app may issue against Dragonfly (pinned image tag v1.29.0).

To record the app's REAL request path (not just a hand-written connection),
boot the Webman child with the seam enabled — `RedisCommandSurfaceGuardTest`
does exactly this:

```php
WebmanTestHarness::boot([
    'KQ_TEST_RECORD_REDIS' => '1',
    'KQ_TEST_REDIS_TRACE_FILE' => $traceFile,
    'KQ_TEST_REDIS_QUEUE_PRODUCE' => '1', // optional redis-queue producer round
]);
```

The seam (`support/RedisRecordBootstrap.php`, registered in
`config/bootstrap.php`) replaces the webman/redis pool's connection creator
so EVERY pooled connection is recorder-wrapped, then flushes the merged trace
to `KQ_TEST_REDIS_TRACE_FILE` when the worker stops. It is inert in
production (early return when the env var is unset).

## Conventions

- Unit tests live next to their service under `tests/unit/` mirroring
  `app/service/`.
- `api/phpunit.xml.dist` configures the `unit` + `integration` suites; CI
  runs them all with the container stack.
- **§13.7 isolation note (P0.6):** the plan's per-class Dragonfly db index via
  `SELECT` + transactional MySQL rollback are DEFERRED to P2. The current
  suite isolates with unique key prefixes instead (`kq:...` keys,
  `{redis-queue}-surf-*` test queues) and cleans up after itself — do not
  introduce shared mutable test keys.
- Anything touching money, entitlements, or published content must be tested
  through the `job_outbox` path (§13.4.5), not a direct queue push.
- Manual P0.6.3 soak + P0.6.4 Dragonfly-restart drill procedures: see
  `tests/Integration/SoakDrill.md`.