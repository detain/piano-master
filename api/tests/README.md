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
   route surface; `WorkerLongevityTest` is the §13.7 do-not-skip suite.

3. **Worker-longevity suite (do not skip)** — `tests/Integration/
   WorkerLongevityTest.php` boots ONE worker and fires 10 000 requests through
   it (Guzzle concurrency 8) across mixed routes, then asserts (a) RSS growth
   < 20 MB after warmup — plan target is 5 MB, the measured delta is reported
   in `/tmp/kq-longevity-summary.json`, (b) zero cross-request data bleed
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
   gracefully. Command count + fence rejections land in
   `/tmp/kq-dragonfly-surface-summary.json`.

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

## Conventions

- Unit tests live next to their service under `tests/unit/` mirroring
  `app/service/`.
- `phpunit.xml.dist` at the repo root configures the `unit` + `integration`
  suites; CI runs them all with the container stack.
- Anything touching money, entitlements, or published content must be tested
  through the `job_outbox` path (§13.4.5), not a direct queue push.