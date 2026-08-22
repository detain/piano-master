# P0.6.3 / P0.6.4 manual soak & drills

Automated regression gate first, then the full manual procedures. Run the
automated gate in CI and locally on every commit; run the manual drills at the
checkpoints marked below (before the P0.9 gate review).

## 0. Regression gate (automated — CI runs this)

`WorkerLongevityTest` boots one real Webman worker and fires 10 000 requests
across the mixed route surface with Guzzle concurrency 8. It asserts:

- worker alive at the end,
- zero cross-request data bleed (each request's unique `X-Request-Id` must be
  echoed back — the §13.4.2 canary),
- worker RSS growth after warmup < 20 MB (CI-safe bound; plan target is 5 MB,
  the measured delta is reported),
- the whole run completes in under 2 minutes (locally ~2-40 s).

It is in the default integration suite (plan §13.7 "do not skip") and tagged
`longevity` so it can run alone:

```bash
cd api
docker compose up -d                      # MySQL + Dragonfly
vendor/bin/phpunit --configuration phpunit.xml.dist --group longevity
```

The measured numbers are printed to stderr (`longevity-summary: {...}`) and
written to a per-run tempnam file whose path is printed as
`longevity-summary-path:`.

If this gate is ever red, stop: the manual soak below is not meaningful until
the 10k run is green again.

## 1. Manual P0.6.3 soak — run before the P0.9 gate review

> **MANUAL P0.6.3 SOAK — RUN BEFORE THE P0.9 GATE REVIEW.**

> **STATUS (2026-08-22): COMPLETE — full results in
> `docs/runbooks/soak-results-2026-08-22.md`.** Verdicts: RSS growth 0 kB,
> p99 3.149 → 2.971 ms, worker never restarted, zero bleed (23.4 M requests).
> Caveats recorded there: the driver originally did not pace to ~28 req/s
> (deadline-only) and a forced MySQL-kill test found the immediate reconnect
> path was broken (500 until the ~50 s pool heartbeat) — Finding A. Both are
> now FIXED: the driver paces `--duration` as a rate limiter, and the MySQL
> lost-connection reconnect is covered by `DbReconnectTest` plus the driver's
> `--reconnect-check` step (see §1f). P0.6.4 drill also run; see the same
> file.

Plan §20 P0.6.3: *One worker, 100 000 requests over an hour, mixed routes.
Expectation: RSS growth < 5 MB, p99 latency at hour-one within 10% of
minute-one, worker never restarted, zero cross-request data bleed. Then leave
it idle overnight and hit it again: the MySQL connection reconnects cleanly.*

### 1a. Start the app (one worker, foreground)

```bash
cd api
# Terminal 1 — the app. Foreground so you can watch it; one worker is the
# plan's soak topology (config/server.php count=1).
WEBMAN_LISTEN=http://127.0.0.1:8787 php start.php start
```

Verify it is ready:

```bash
curl -s http://127.0.0.1:8787/readyz        # {"status":"ready",...}
```

### 1b. Run the soak driver

```bash
# Terminal 2 — the load (100 000 requests paced across 3600 s at ~28 req/s,
# the plan's profile; --duration is a RATE LIMITER, not a deadline).
php tests/scripts/soak.php \
  --base=http://127.0.0.1:8787 \
  --total=100000 \
  --duration=3600 \
  --concurrency=16 \
  --summary=/tmp/kq-soak-summary.json
```

Quick smoke before the real run (validates setup in seconds — the run takes
`--duration` because of pacing):

```bash
php tests/scripts/soak.php --total=200 --duration=5 --concurrency=16
```

The driver prints a one-line progress update to stderr every minute and at
the end prints the per-minute table, the verdicts, and
`soak-summary: <path>`.

### 1c. How to read p99 from the summary output

The plan's latency metric is **p99 at hour-one within 10% of minute-one**. In
the per-minute table, the `p99` column is the 99th-percentile latency for that
minute in milliseconds. The verdicts section compares the LAST minute's p99 to
the FIRST minute's p99:

```text
== P0.6.3 soak verdicts ==
rss_growth_lt_5mb                             PASS
p99_last_within_10pct_of_minute_one           PASS
zero_bleed_mismatches                         PASS
worker_alive_at_end                           PASS
```

Every line must read PASS. The machine-readable copy (for the report) is the
summary JSON, notably:

- `rss_first_kb` / `rss_last_kb` / `rss_growth_kb` (must be < 5120 kB),
- `first_minute_p99_ms` / `last_minute_p99_ms` (last ≤ first × 1.10),
- `bleed_mismatches` (must be 0),
- `per_minute[].p99_ms` (the series to eyeball for drift).

The `rss_kb` column samples the worker's VmRSS once per minute (discovered via
`runtime/webman.pid` → `/proc/<master>/task/<master>/children`). If the app was
started with `count=1`, that is the single serving worker. If RSS shows `n/a`,
the pid file was not found — pass `--pid=<master-pid>` (from `$!` or
`runtime/webman.pid`).

### 1d. What to record

Copy into the P0.9 gate record:

1. `git rev-parse HEAD` (api workspace), Dragonfly + MySQL image tags
   (`docker compose -f api/docker-compose.yml ps`),
2. the final soak summary JSON (or at least the verdicts + the p99 series),
3. worker `restart_interval` = 0 / `worker_alive_at_end` = PASS (worker never
   restarted),
4. the app log tail from Terminal 1 (no "MySQL server has gone away" during
   the hour; only request log lines),
5. elapsed wall time and the exact command line used.

### 1e. Idle-overnight reconnect check

Plan P0.6.3 second half: leave the SAME worker idle overnight, then hit it
again in the morning — the MySQL + Dragonfly connections must reconnect cleanly
(no "MySQL server has gone away" on the first request). The app's
`config/database.php` / `config/redis.php` keep `wait_timeout` above the idle
window and short connect/read timeouts so the failure would be fast if it
existed.

```bash
# Morning — same terminal 1 worker, no restart:
curl -s http://127.0.0.1:8787/db/version     # must return db_version, not an error
curl -s http://127.0.0.1:8787/cache/now      # must return a value (read-through)
curl -s http://127.0.0.1:8787/readyz         # {"status":"ready",...}
```

Record: the three responses + whether the worker process id is unchanged
(`cat runtime/webman.pid`, compare to the previous day's).

### 1f. Deterministic MySQL reconnect check

The overnight-idle check above only exercises reconnect if MySQL actually
closed the connection during the idle window (the server's `wait_timeout` is
8 h, so a one-night idle does NOT). To exercise the reconnect path
deterministically — kill the worker's live MySQL connection and verify the
FIRST request reconnects cleanly (plan §13.4.2: no "MySQL server has gone
away" on the first request) — the soak driver has a dedicated step:

```bash
# Same running worker as §1a (no restart). Kills the worker's MySQL
# connection server-side, then immediately GETs /db/version:
php tests/scripts/soak.php --base=http://127.0.0.1:8787 --reconnect-check
# -> mysql_reconnect_after_kill PASS; exit 0. Non-zero exit on failure.
```

It can also be run as a pre-check before the long soak:

```bash
php tests/scripts/soak.php --base=http://127.0.0.1:8787 --reconnect-check --total=100000 --duration=3600
```

The regression gate covers the same contract automatically:
`api/tests/Integration/DbReconnectTest.php` (kill worker connection + assert
first `/db/version` is 200) runs in the default integration suite.

## 2. Manual P0.6.4 drill — Dragonfly restart mid-consume

> **MANUAL P0.6.4 DRILL.**

Plan §20 P0.6.4: *Confirm the queue plugin's blocking pop, delayed-zset, and
retry paths all behave under a Dragonfly restart mid-consume.* The point is
that a cached/queued workload survives the cache server bouncing: the consumer
reconnects, the in-flight BRPOP resumes, and delayed jobs are still promoted.

Prerequisites: stack up (`docker compose -f api/docker-compose.yml up -d`).

### 2a. Start the consumer

```bash
cd api
# Terminal 1 — the consume path (built on the vendored webman/redis-queue
# RedisConnection; prints consumed/delayed/reconnect observation lines).
php tests/scripts/queue-consumer.php --queue=kq:drill:queue
```

### 2b. Enqueue jobs (Terminal 2)

```bash
cd api
php -r '
require "vendor/autoload.php";
$r = new Webman\RedisQueue\RedisConnection();
$r->connectWithConfig(["host"=>"127.0.0.1","port"=>6379,"db"=>0,"auth"=>"","timeout"=>2,"ping"=>55,"prefix"=>""]);
$now = time();
$r->lpush("{redis-queue}-waitingkq:drill:queue", json_encode(["id"=>"drill-1","time"=>$now,"delay"=>0,"attempts"=>0,"queue"=>"kq:drill:queue","data"=>["phase"=>"before-restart"]]));
$r->zadd("{redis-queue}-delayed", $now+10, json_encode(["id"=>"drill-2","time"=>$now,"delay"=>10,"attempts"=>0,"queue"=>"kq:drill:queue","data"=>["phase"=>"delayed-before-restart"]]));
echo "enqueued drill-1 (immediate) + drill-2 (delayed 10s)\n";
'
```

### 2c. Restart Dragonfly mid-consume

While the consumer is blocked in BRPOP (or has just consumed `drill-1`), from
Terminal 3:

```bash
cd /home/sites/piano-master
sg docker -c 'docker compose -f api/docker-compose.yml restart dragonfly'
# watch the consumer log:
```

The consumer must print a connection-loss line (`connection lost` /
`reconnected`) and keep consuming `drill-2` when it becomes due (delayed →
promoted → BRPOP → consumed). Also enqueue one more job right after the
restart to prove writes succeed on the fresh connection:

```bash
php -r '
require "vendor/autoload.php";
$r = new Webman\RedisQueue\RedisConnection();
$r->connectWithConfig(["host"=>"127.0.0.1","port"=>6379,"db"=>0,"auth"=>"","timeout"=>2,"ping"=>55,"prefix"=>""]);
$r->lpush("{redis-queue}-waitingkq:drill:queue", json_encode(["id"=>"drill-3","time"=>time(),"delay"=>0,"attempts"=>0,"queue"=>"kq:drill:queue","data"=>["phase"=>"after-restart"]]));
echo "enqueued drill-3\n";
'
```

### 2d. Retry path

Restart the consumer with failure simulation and enqueue one job; the package
must be consumed, failed, re-queued to the delayed zset, promoted back, and
consumed again:

```bash
# Terminal 1 (restart consumer):
php tests/scripts/queue-consumer.php --queue=kq:drill:queue --fail-every=1
# Terminal 2 (enqueue one job, then watch two consume lines + one retry line):
php -r '
require "vendor/autoload.php";
$r = new Webman\RedisQueue\RedisConnection();
$r->connectWithConfig(["host"=>"127.0.0.1","port"=>6379,"db"=>0,"auth"=>"","timeout"=>2,"ping"=>55,"prefix"=>""]);
$r->lpush("{redis-queue}-waitingkq:drill:queue", json_encode(["id"=>"drill-retry","time"=>time(),"delay"=>0,"attempts"=>0,"queue"=>"kq:drill:queue","data"=>["retry"=>true]]));
echo "enqueued drill-retry\n";
'
```

Expected observation lines: `[consumer] consumed ...`, `[retry] failed package
re-queued to delayed ...`, `[delayed] promoted ...`, `[consumer] consumed ...`
again.

### 2e. What to record

1. The full consumer log (copy verbatim — the observation lines are the
   evidence: consume → connection loss → reconnect → delayed promotion →
   consume),
2. Dragonfly restart command + wall-clock timestamps of the restart,
3. whether any job was lost or duplicated (id uniqueness check in the log),
4. the Redis surface commands observed (BRPOP, ZREVRANGEBYSCORE, ZREM, LPUSH,
   ZADD — all already pinned in `dragonfly_command_surface.php`).

## 3. Dragonfly v1.29.0 findings → ADR before the P0.9 gate

Plan §13.5: *any surprise goes into an ADR.* The P0.6.4 audit found two Redis
7 commands that Dragonfly v1.29.0 does NOT implement:

- `OBJECT ENCODING` → `ERR unknown command 'OBJECT'`
- `COPY` → `ERR unknown command 'COPY'`

They are kept OUT of the app surface on purpose and documented with
provenance in `tests/Support/dragonfly_command_surface.php` (`findings`
block) and in the `DragonflyCommandSurfaceTest` docblock. Before the P0.9 gate
review, move these (plus the `CLIENT TRACKING` RESP2 rejection note) into a
proper ADR at `docs/adr/NNNN-dragonfly-command-surface.md` following the
P0.1.4 ADR practice (one page: context, options, decision, consequences), and
cross-link it from this file's section 3.