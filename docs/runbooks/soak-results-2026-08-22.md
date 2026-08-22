# P0.6.3 soak + P0.6.4 drill results — 2026-08-22

Manual backend soak and failover drill per `api/tests/Integration/SoakDrill.md`
(§1 P0.6.3, §2 P0.6.4), required before the P0.9 gate review.

## 0. Environment

| Item | Value |
|---|---|
| API HEAD (`git rev-parse HEAD` in `/api`) | `840d566` (master; atop `d23985b`) |
| Monorepo HEAD | `840d566` |
| MySQL image | `mysql:8.0.43` (container `keyquest-mysql`, host-network, `127.0.0.1:3306`) |
| Dragonfly image | `ghcr.io/dragonflydb/dragonfly:v1.29.0` (container `keyquest-dragonfly`, host-network, `127.0.0.1:6379`) |
| API process | `php start.php start`, `WEBMAN_LISTEN=http://127.0.0.1:8787`, `count=1` (config/server.php default) |
| Master PID / worker PID | `3727624` / `3727643` (unchanged for the whole session, 12:30:30 → 15:0x UTC) |
| `docker compose up` | **NOT used** — the Docker bridge is broken on this box; containers run with `docker run --network host` (verified via `docker inspect`: `NetworkMode=host`) |

## 1. P0.6.3 soak

The documented command completes far faster than the doc implies: the driver's
`--duration` is a hard **deadline**, not a rate limiter. With a fast local
server, 100 000 requests at concurrency 16 exhaust in ~15 s (~6500 req/s), not
1 h at ~28 req/s. We therefore ran **two** phases:

1. the documented command exactly as written (`--total=100000 --duration=3600`), and
2. a sustained-hour run (`--total=30000000 --duration=3600`) in which the
   3600 s deadline governs, producing the real per-minute RSS/p99 series over a
   full hour at the server's actual sustained rate (~6500 req/s, 23.4 M requests).

Both runs share the same worker process; the worker never restarted.

### 1a. Documented-command burst run

```bash
php tests/scripts/soak.php --base=http://127.0.0.1:8787 --total=100000 --duration=3600 --concurrency=16 --summary=/tmp/kq-soak-summary.json
```

- Total requests: **100 000** — elapsed **15.3 s** — **6525.3 req/s**
- `bleed_mismatches`: **0**
- RSS first/last: **40408 / 40408 kB** (growth **0 kB**)
- p99 minute-one: **2.737 ms**
- Verdicts: `rss_growth_lt_5mb PASS`, `p99_last_within_10pct_of_minute_one PASS`, `zero_bleed_mismatches PASS`, `worker_alive_at_end PASS`
- Summary JSON: `/tmp/kq-soak-summary.json`

### 1b. Sustained-hour run (per-minute series)

```bash
php tests/scripts/soak.php --base=http://127.0.0.1:8787 --total=30000000 --duration=3600 --concurrency=16 --summary=/tmp/kq-soak-summary-hour.json
```

- Total requests: **23 436 688** — elapsed **3600.0 s** — **6510.2 req/s**
- `bleed_mismatches`: **0**
- Errors: **0** across all 61 per-minute buckets
- RSS first/last (driver sample): **40408 / 40408 kB** (growth **0 kB**)
- p99 minute-one: **3.149 ms**; p99 minute-60 (hour-one): **2.971 ms** (ratio 0.943 → p99 *improved* ~5.6%; within the 10 % band)
- Worker alive at end: **PASS** (master/worker PIDs unchanged)
- Verdicts: all **PASS**
- Summary JSON: `/tmp/kq-soak-summary-hour.json`

Per-minute table (count / p99 ms / rss kB — p50/p95 in the summary JSON):

| minute | count | p99 ms | rss kB | minute | count | p99 ms | rss kB |
|---|---|---|---|---|---|---|---|
| 1 | 390533 | 3.15 | 40408 | 31 | 392669 | 3.11 | 40408 |
| 2 | 389234 | 3.36 | 40408 | 32 | 388574 | 3.05 | 40408 |
| 3 | 394061 | 3.15 | 40408 | 33 | 392006 | 2.94 | 40408 |
| 4 | 387585 | 3.33 | 40408 | 34 | 389516 | 3.04 | 40408 |
| 5 | 393037 | 3.03 | 40408 | 35 | 390439 | 3.10 | 40408 |
| 6 | 388124 | 3.13 | 40408 | 36 | 390451 | 3.24 | 40408 |
| 7 | 392526 | 3.13 | 40408 | 37 | 389431 | 3.06 | 40408 |
| 8 | 390320 | 3.29 | 40408 | 38 | 389053 | 3.17 | 40408 |
| 9 | 391711 | 3.06 | 40408 | 39 | 392453 | 3.02 | 40408 |
| 10 | 390437 | 3.14 | 40408 | 40 | 389997 | 3.19 | 40408 |
| 11 | 390455 | 3.06 | 40408 | 41 | 390459 | 3.27 | 40408 |
| 12 | 392201 | 3.03 | 40408 | 42 | 390521 | 3.12 | 40408 |
| 13 | 393179 | 2.92 | 40408 | 43 | 385534 | 3.10 | 40408 |
| 14 | 390361 | 3.03 | 40408 | 44 | 388480 | 3.15 | 40408 |
| 15 | 392969 | 3.07 | 40408 | 45 | 385933 | 3.23 | 40408 |
| 16 | 392364 | 3.23 | 40408 | 46 | 394754 | 3.00 | 40408 |
| 17 | 388565 | 3.10 | 40408 | 47 | 389943 | 3.19 | 40408 |
| 18 | 390880 | 3.26 | 40408 | 48 | 390344 | 3.19 | 40408 |
| 19 | 393186 | 3.10 | 40408 | 49 | 391749 | 3.01 | 40408 |
| 20 | 391668 | 3.10 | 40408 | 50 | 392364 | 3.16 | 40408 |
| 21 | 388691 | 3.21 | 40408 | 51 | 392088 | 3.12 | 40408 |
| 22 | 385060 | 3.25 | 40408 | 52 | 393416 | 3.09 | 40408 |
| 23 | 392370 | 3.02 | 40408 | 53 | 387345 | 3.26 | 40408 |
| 24 | 389905 | 3.10 | 40408 | 54 | 390764 | 3.24 | 40408 |
| 25 | 388060 | 3.17 | 40408 | 55 | 389386 | 2.95 | 40408 |
| 26 | 387960 | 3.32 | 40408 | 56 | 392150 | 3.19 | 40408 |
| 27 | 391231 | 3.01 | 40408 | 57 | 388946 | 3.33 | 40408 |
| 28 | 389720 | 3.22 | 40408 | 58 | 390620 | 3.13 | 40408 |
| 29 | 394365 | 2.94 | 40408 | 59 | 393252 | 3.06 | 40408 |
| 30 | 391991 | 3.25 | 40408 | 60 | 391288 | 2.97 | 40408 |
| 61* | 14 | 2.21 | n/a | | | | |

\* minute 61 = the 14-request tail as the deadline hit.

### 1c. RSS growth — independent 5-minute monitor

The driver's own per-minute RSS sampling only populated minute 1 (see
§5 Finding D), so an independent monitor sampled the worker's `VmRSS` every
5 minutes for the whole session (`/proc/<worker>/status`, worker pid 3727643).
**31/31 samples are exactly 40408 kB** — RSS growth **0 kB** over the hour soak,
the 25-minute idle window, and the drill.

| time (UTC) | rss kB | time (UTC) | rss kB |
|---|---|---|---|
| 12:31:36 | 40408 | 13:51:36 | 40408 |
| 12:36:36 | 40408 | 13:56:36 | 40408 |
| 12:41:36 | 40408 | 14:01:36 | 40408 |
| 12:46:36 | 40408 | 14:06:36 | 40408 |
| 12:51:36 | 40408 | 14:11:36 | 40408 |
| 12:56:36 | 40408 | 14:16:36 | 40408 |
| 13:01:36 | 40408 | 14:21:36 | 40408 |
| 13:06:36 | 40408 | 14:26:36 | 40408 |
| 13:11:36 | 40408 | 14:31:36 | 40408 |
| 13:16:36 | 40408 | 14:36:36 | 40408 |
| 13:21:36 | 40408 | 14:41:36 | 40408 |
| 13:26:36 | 40408 | 14:46:36 | 40408 |
| 13:31:36 | 40408 | 14:51:36 | 40408 |
| 13:36:36 | 40408 | 14:56:36 | 40408 |
| 13:41:36 | 40408 | 15:01:37 | 40408 |
| 13:46:36 | 40408 | | |

### 1d. Worker restart / bleed / app log

- `restart_interval` = **0**, `worker_alive_at_end` = **PASS**. Master pid
  3727624 and worker pid 3727643 constant from start (12:30:30) to shutdown.
- **Zero cross-request data bleed**: `bleed_mismatches` = 0 in both runs. The
  canary is meaningful — `app/Middleware/RequestIdMiddleware.php` echoes a
  well-formed inbound `X-Request-Id` (soak ids `kq-soak-<i>-<hex>` match
  `^[A-Za-z0-9._-]{1,64}$`).
- App request log: the middleware writes one JSON line per request to stdout;
  the PTY buffer rolls at ~6500 lines/s, so a full-hour tail is not
  preservable. Evidence of "no errors during the hour": driver `errors` = 0
  per minute (authoritative), periodic scans of the app log window for
  `gone away|ERROR|exception|5xx|4xx` found nothing, and `runtime/logs/workerman.log`
  shows only the start line (no restarts, no errors).

### 1e. Idle + MySQL reconnect (partial)

The soak ended ~13:38 UTC. The worker was left idle **~25 minutes** (13:38 →
14:03) and then hit again:

- `GET /db/version` → `{"db_version":"8.0.43"}` **200** in 0.8 ms
- `GET /cache/now` → `{"value":"2026-08-22T14:04:57+00:00","from_cache":true,"ttl_seconds":60}` **200**
- `GET /readyz` → `{"status":"ready","mysql":true,"dragonfly":true}` **200**
- Worker pid unchanged (3727624); 50-request burst → 50× **200**

**Caveat (partial result):** MySQL `wait_timeout` is 28800 s (8 h), so a
25-minute idle does **not** force a server-side connection close. This run
proves "worker survives idle and serves correctly" but does **not** exercise
the reconnect path. A full overnight idle would. To still obtain evidence,
we ran a forced-disconnect test (next section).

### 1f. Forced-disconnect test — **FINDING (gate-blocking)**

To exercise the reconnect path deterministically, the worker's live MySQL
connection (processlist id 2488) was `KILL`ed server-side and `/db/version`
was hit immediately:

1. First request → **HTTP 500** `SQLSTATE[HY000]: General error: 2006 MySQL server has gone away`
2. Second request → **HTTP 500** again (no recovery)
3. ~5 minutes later (after the pool's 50 s heartbeat had fired) → **HTTP 200**, fresh connection (processlist id 2494)

So the app **does not reconnect on the request that hits the dead connection**;
it self-heals only after the pool heartbeat (up to ~50 s) clears the dead
connection. The plan's P0.6.3 expectation — "the MySQL connection reconnects
cleanly, no 'MySQL server has gone away' on the first request" — is **NOT MET**
by the current skeleton.

**Root cause** (verified with four controlled CLI probes reproducing the app's
exact config):
- Raw Illuminate (no webman pool): reconnects cleanly after `KILL` (new
  connection, retry succeeds).
- Webman pool path: illuminate's lost-connection retry (`tryAgainIfCausedByLostConnection`
  → `reconnect()` → `DatabaseManager::reconnect($name)` → `connection($name)`)
  is defeated by **`Webman\Database\DatabaseManager::connection()` returning
  the `Context`-cached dead connection** instead of a fresh one — the dead PDO
  is never replaced, so the retry re-runs on the dead connection and the
  original 500 propagates.
- The reconnector IS callable, `causedByLostConnection()` returns true,
  transaction level is 0 — the only broken link is the webman
  `Context` cache in `connection()` during reconnect.

Suggested fix (needs P1 planning/ADR, not done here): make webman's
`connection()`/`reconnect()` invalidate the `Context` cache (or health-check
the pooled connection before returning it from `Context`).

Impact: a MySQL restart / network blip / wait_timeout expiry causes up to
~50 s of 500s before the heartbeat replaces the connection. The overnight-idle
scenario would likely self-heal (heartbeat keeps pinging during idle and closes
dead connections within ~50 s), but the window is racy.

## 2. P0.6.4 drill — Dragonfly restart mid-consume

Prerequisite check: **no `job_outbox` table exists** in the schema
(`docker/mysql-init/01_schema.sql` creates only `skeleton_echo`) and **no
queue-consumer process** is wired in `config/process.php` — both are P1 items.
The drill therefore used the documented script consumer
(`tests/scripts/queue-consumer.php`, built on the vendored
webman/redis-queue `RedisConnection`).

### 2a/2b. Consumer + enqueue (pre-restart)

```bash
php tests/scripts/queue-consumer.php --queue=kq:drill:queue          # terminal 1
# terminal 2: enqueue drill-1 (immediate) + drill-2 (delayed 10 s) per SoakDrill §2b
```

Consumer log (verbatim, pre-restart):

```
[consumer] connected to 127.0.0.1:6379, queue=kq:drill:queue, waiting={redis-queue}-waitingkq:drill:queue, delayed={redis-queue}-delayed
[consumer] consumed (5): {"id":"drill-1","time":1787406146,"delay":0,"attempts":0,"queue":"kq:drill:queue","data":{"phase":"before-restart"}}
[delayed] promoted {"id":"drill-2","time":1787406146,"delay":10,"attempts":0,"queue":"kq:drill:queue","data":{"phase":"delayed-before-restart"}}
[consumer] consumed (8): {"id":"drill-2","time":1787406146,"delay":10,"attempts":0,"queue":"kq:drill:queue","data":{"phase":"delayed-before-restart"}}
```

drill-1 consumed once; drill-2 promoted from the delayed zset and consumed
once. (The `consumed (N): ` empty lines interleaved are BRPOP timeouts — see
§5 Finding E.)

### 2c. Dragonfly restart mid-consume

- `docker restart keyquest-dragonfly` at **14:47:18 UTC** (restart exit 0);
  container healthy again at **14:48:10 UTC** (~52 s, incl. healthcheck).
  Note: `docker compose restart` was NOT used (bridge broken); `docker restart`
  preserves the host-network container config.
- Expected observation: `connection lost` / `reconnected` lines — **NONE
  appeared**. The consumer's BRPOP did not throw; after the restart its BRPOP
  began returning `[]` immediately (fast-fail) instead of blocking, and the
  consumer treated each `[]` as an empty consumed package (~1/s spam).
- **No job loss**: `drill-3` enqueued at 14:49:34 (post-restart, fresh
  connection) was consumed immediately and exactly once:

```
[consumer] consumed (677): {"id":"drill-3","time":1787410174,"delay":0,"attempts":0,"queue":"kq:drill:queue","data":{"phase":"after-restart"}}
```

Queue drained to `LLEN=0`; delayed zset `ZCARD=0` after cleanup.

### 2d. Retry path

Consumer restarted with `--fail-every=1`; one job `drill-retry` enqueued. The
full cycle is observed repeatedly (fail-every=1 fails **every** package, so the
cycle loops by design):

```
[consumer] consumed (6): {"id":"drill-retry","time":1787410549,"delay":0,"attempts":0,"queue":"kq:drill:queue","data":{"retry":true}}
[retry] failed package re-queued to delayed (retry at 1787410551)
[delayed] promoted {"id":"drill-retry","time":1787410549,"delay":0,"attempts":0,"queue":"kq:drill:queue","data":{"retry":true}}
[consumer] consumed (8): {"id":"drill-retry","time":1787410549,"delay":0,"attempts":0,"queue":"kq:drill:queue","data":{"retry":true}}
```

Retry path verified: consume → fail → delayed-zset → promote → consume.

### 2e. Drill outcomes

| Check | Result |
|---|---|
| Blocking pop before/after restart | BRPOP works; post-restart it fast-returns `[]` instead of throwing (Finding E) |
| Delayed-zset promotion | PASS (drill-2 pre-restart, drill-retry) |
| Retry path | PASS (consume → fail → delayed → promote → consume) |
| Job loss | **NONE** — drill-1/2/3 each consumed exactly once |
| Job duplication | drill-1/2/3: none. `drill-retry` consumed many times — expected (fail-every=1 always fails) |
| Explicit `connection lost`/`reconnected` observation | **MISSING** (consumer does not detect Dragonfly restart — Finding E) |
| Redis surface observed | BRPOP, ZREVRANGEBYSCORE, ZREM, LPUSH, ZADD (all already pinned in `tests/Support/dragonfly_command_surface.php`) |
| Outbox-backed queue | **N/A** — `job_outbox` not in schema (P1) |

## 3. Verdict summary

| Plan expectation | Result |
|---|---|
| 100k requests / 1 h | PASS (23.4 M requests / 3600 s in the sustained run; the documented 100k command completes in 15 s — driver does not pace, Finding C) |
| RSS growth < 5 MB | PASS (0 kB — 31 independent samples, all 40408 kB) |
| p99 hour-one within 10% of minute-one | PASS (3.149 → 2.971 ms, −5.6 %) |
| Worker never restarted | PASS (PIDs constant) |
| Zero cross-request bleed | PASS (0 mismatches across 23.5 M requests) |
| Clean MySQL reconnect after idle | **FAIL (immediate path)** — 500 "MySQL server has gone away" after server-side kill; self-heals in ~50 s via pool heartbeat. Finding A |
| Queue survives Dragonfly restart mid-consume | PASS for the achievable subset (no job loss; delayed/retry paths work). Finding E: consumer observability gap + outbox N/A (P1) |

## 4. What is N/A until P1

- `job_outbox` table (not in schema; only `skeleton_echo` exists).
- Real queue-consumer process in `config/process.php` (currently an empty
  inventory; the drill used the script consumer).
- The §3 ADR move (`OBJECT`/`COPY`/`CLIENT TRACKING` findings → ADR) is a
  separate documentation task, not part of this run.

## 5. Findings

- **A (gate-blocking). MySQL reconnect is broken on the immediate path.**
  `Webman\Database\DatabaseManager::connection()` returns the `Context`-cached
  dead connection during illuminate's lost-connection retry, so the PDO is
  never replaced and the retry re-runs on the dead connection. First request
  after a connection kill returns 500; recovery only after the pool's ~50 s
  heartbeat. Verified by 4 CLI probes; raw Illuminate reconnects fine. Fix:
  invalidate/health-check the `Context`-cached connection (P1 + ADR).
- **B. Soak driver `soak.php:140` bug.** `$total` is used in the progress
  closure but not in its `use` list → a PHP `Warning: Undefined variable`
  every minute, the per-minute progress line never prints (DivisionByZeroError
  inside `sprintf` arg evaluation is swallowed by Guzzle's promise chain), and
  the per-minute RSS sampling (which sits after the progress block) runs only
  in minute 1. The final per-minute latency/bleed/error data and verdicts are
  unaffected. Fix: add `$total` to the closure `use` list.
- **C. Soak driver does not pace.** `--duration` is a deadline, not a rate
  limiter; the plan's ~28 req/s profile is not achievable with the committed
  driver. The documented 100k command exhausts in ~15 s locally. Use a larger
  `--total` (as done here) to make the deadline govern.
- **D. RSS sampling in the driver is single-sample.** Because of B, the
  summary's per-minute `rss_kb` is populated only for minute 1. The
  independent 5-minute monitor is the authoritative RSS series (0 kB growth).
- **E. Dragonfly BRPOP timeout/restart shape.** Dragonfly + phpredis returns
  an **empty array** (`[]`) — not `null`/`false` — on BRPOP timeout, and
  returns `[]` fast-fail during/after a server restart instead of throwing.
  `tests/scripts/queue-consumer.php` treats `[]` as a consumed empty package
  (spurious `consumed (N): ` lines, inflated processed counter, `--fail-every`
  miscounts) and never prints its `connection lost`/`reconnected` lines.
  Fix for P1 consumer: treat `[]` as timeout (same as null), and detect
  connection loss by pinging or catching reconnect failures.

## 6. Cleanup state

- API process stopped cleanly (SIGTERM, Workerman graceful exit 0, port 8787 free).
- No zombie `php start.php`/`soak.php`/`queue-consumer` processes remain.
- `docker ps`: `keyquest-mysql` (Up 15 h) and `keyquest-dragonfly`
  (Up, healthy) both running.
- Drill Redis keys removed (`{redis-queue}-waitingkq:drill:queue`,
  `{redis-queue}-delayed`, probe key).
- `make lint` green.