# Load baseline — P0.6.5, P0.6.6 (plan §13.6, §20)

k6 scenarios and the measured baseline for the KeyQuest API skeleton, run on
2026-08-22 against commit `P0.6-C` (see git log). This document states the
methodology precisely, the caveats that come with the proxies, the measured
numbers, and how they relate to the plan §13.6 targets (≥ 5 000 req/s cached
reads, ≥ 800 req/s authenticated writes, p99 < 120 ms on a 4-vCPU reference
node).

The reload and failover drills (P0.6.6) live in `drills.md` in this
directory. The P0.6.3 soak and P0.6.4 Dragonfly-restart manual procedures
stay as documented in `tests/Integration/SoakDrill.md` — this page covers the
k6 baseline plus the new P0.6.6 drills.

## Box and methodology (honest-approximation notes)

- **Host**: 64-core / 251 GB — NOT the plan's 4-vCPU reference node. To
  approximate the reference, the server runs with **4 workers**
  (`WEBMAN_COUNT=4`) **pinned to CPUs 0–3** (`taskset -c 0-3`). k6 is pinned
  to CPUs 4–7 (`taskset -c 4-7`) so the client does not steal the server's
  CPUs. Both client and server still share the machine (interference from
  other tenants/processes is possible); on a true reference node the client
  would sit elsewhere. Treat the numbers as a **lower/upper band, not exact
  reference values** — reads are so far over target that the difference does
  not matter; writes are so far under target that the storage difference
  dominates (below).
- **Server start (select loop — the production default)**
  ```bash
  cd api
  WEBMAN_COUNT=4 taskset -c 0-3 php start.php start   # foreground, or nohup+log
  ```
  The committed `config/server.php` default `count` stays **1** so the
  integration suite (single-child boot, Redis trace seam, longevity RSS
  measurement) is deterministic; load runs pass `WEBMAN_COUNT=4`.
- **Request logging stays ON.** `RequestIdMiddleware` writes one JSON line
  per request to STDOUT (the production shape — §13.6 observability). Every
  number below includes that cost. At 30k+ req/s the log is a real but
  non-blocking cost; do not "optimize" the measurement by removing it.
- **k6 version**: k6 v2.2.0 (`~/bin/k6`). Scenarios in this directory:
  `catalog-reads.js`, `db-reads.js`, `auth-writes.js`,
  `outbound-fanout.js` (coroutine ADR benchmark, see
  `docs/adr/0002-coroutine-posture.md`).
- Run form: `ramping-vus` — ramp to `VUS`, hold `DURATION`, then the summary
  is the steady-state plateau. VU/duration overridable via `-e VUS=.. -e
  DURATION=..`.

## Scenarios and proxies

### catalog-reads.js — cached-read proxy (Dragonfly read-through)

`GET /cache/now` reads `kq:boot_time` through Dragonfly (a single Redis GET
on a hit; GET + SET NX + GET on a 60 s TTL miss). **This is a PROXY for
"cached catalog reads"** — the Phase-1 catalog endpoints do not exist yet.
The proxy matches the real shape: one in-process Redis round trip, small
JSON body, no DB. Expect the real catalog reads to be within ±50% of this
number (they will add an index lookup on MySQL or a precomputed SongPack
lookup).

### db-reads.js — uncached MySQL read (informational)

`GET /db/version` runs `SELECT VERSION()` through the MySQL pool. This is the
proxy for "uncached catalog read": one MySQL round trip per request. There
is no §13.6 target for this class; it is recorded so cached vs uncached read
cost can be compared on the same box.

### auth-writes.js — authenticated-write proxy (Eloquent INSERT)

`POST /auth/echo` (Bearer `dev-token`, small JSON body) runs
`DevAuthMiddleware` + one Eloquent `INSERT` into `skeleton_echo`. **This is a
PROXY for the Phase-2 authenticated progress writes**: the real routes write
several rows and a `job_outbox` entry (§13.4.5), so expect the real write
budget to be lower. The proxy measures the cheapest honest "auth + one DB
write" shape.

### queue-drain scenario — N/A (documented gap)

A queue-drain load scenario is **not applicable in P0.6**: no queue consumer
exists yet (config/process.php is empty; `tests/scripts/queue-consumer.php`
is a manual smoke script). It will land with the P1 queue consumer —
`webman/redis-queue` is already wired (the 
`RedisCommandSurfaceGuardTest` producer round uses it) and the consumer
process will live in `app/Process/QueueConsumer.php` with a matching
`queue-drain.js` here.

## Measured baseline (2026-08-22, select loop, 4 workers pinned 0–3)

### Cached reads — GET /cache/now

| metric | value |
|---|---|
| VUs / hold | 150 / 30 s (plus 30 s ramp) |
| req/s | **35 611** (run 1), **31 602** (run 2, p99 captured) |
| avg | 3.07–3.49 ms |
| p50 | ~3.3–3.9 ms |
| p90 | 4.67–4.85 ms |
| p95 | 6.32–6.57 ms |
| p99 | **10.12 ms** |
| max | 40.65 ms |
| errors | **0** (2 136 818 / 1 896 290 requests) |

**§13.6: MET with ~6.3x headroom** (≥ 5 000 req/s target, p99 10 ms « 120 ms).
Headroom is large because a cached read is one in-process Redis GET; the
bottleneck at 150 VUs was the client/socket layer, not PHP (4 workers used
~0.2 core each — see the load summary in the commit notes).

### MySQL reads — GET /db/version (informational)

| metric | value |
|---|---|
| req/s | **18 047** |
| p99 | 10.63 ms |
| errors | 0 (721 977 requests) |

### Authenticated writes — POST /auth/echo

| metric | value |
|---|---|
| VUs / hold | 60 / 20 s (plus 20 s ramp) |
| req/s | **370** |
| avg | 116–121 ms |
| p50 | 112–134 ms |
| p95 | 200–208 ms |
| p99 | **303–317 ms** |
| max | 421 ms |
| errors | 0 (14 869 requests) |

**§13.6: NOT MET** (target ≥ 800 req/s, p99 < 120 ms).

**Bottleneck analysis (the plan demands this):** the wall is the **MySQL
commit path on this box's storage**, not the API:

- PHP workers are idle: 370 req/s × ~7 ms server-side = ~2.6 core-seconds/s
  across 4 workers (65% of one core). Not worker-bound.
- The MySQL pool is fine: `max_connections=5`/worker, 20 total; latency at 5
  VUs is 11.7 ms avg (no pool starvation). Not pool-bound.
- Dragonfly is not involved in writes.
- k6 client is not saturated: latency grows linearly with VUs (queueing at
  the server's DB), throughput plateaus at ~370 req/s at every VU count
  (5/15/30/60 all ~360–370 req/s).
- **MySQL commit fsync is the serial point**: `innodb_flush_log_at_trx_commit=1`
  + `sync_binlog=1` (MySQL 8 defaults) → two fsyncs per commit. Direct
  measurement on this box: `sync` costs ~3.8 ms on `/dev/md0` (the Docker
  named-volume backing store); raw concurrent `INSERT` loops inside the
  container top out at ~400 commits/s. The API's 370 req/s sits just below
  that wall (InnoDB group commit batches ~3 transactions per fsync cycle).
- **Proof by toggle**: with `SET GLOBAL innodb_flush_log_at_trx_commit=2;
  SET GLOBAL sync_binlog=0` (async fsync — a legitimate durability trade-off
  for non-critical telemetry-style writes) the SAME API sustains **8 795
  req/s** (avg 4.81 ms, p95 9.3 ms) — a **23.8x** improvement. Settings were
  restored to 1/1 immediately after the measurement.

**What this means for §13.6**: the write target is not met on THIS box with
MySQL's default durability because the host storage fsyncs slowly. On the
plan's reference 4-vCPU node the commit path (NVMe or `flush=2`) determines
whether the target is met; the API layer contributes only ~1–5 ms per write
and is not the limiter. Track host storage + MySQL durability config in the
Phase-1 capacity review.

## Reproducing

```bash
# services (host-network containers on this box; see api/docker-compose.yml)
docker ps | grep -E 'keyquest-(mysql|dragonfly)'

# server (4 workers, pinned)
cd api
WEBMAN_COUNT=4 taskset -c 0-3 php start.php start

# client (pinned away from the server)
cd api/tests/load
taskset -c 4-7 ~/bin/k6 run catalog-reads.js          # reads: VUS=150 DURATION=30s default
taskset -c 4-7 ~/bin/k6 run db-reads.js               # mysql reads
taskset -c 4-7 ~/bin/k6 run auth-writes.js            # writes: VUS=60 DURATION=20s default
```

## Drills (P0.6.6)

Drill A (graceful reload under load) and Drill B (Dragonfly kill/failover)
were executed and recorded in **`drills.md`** — both PASSED with zero failed
requests, including the outage window (degraded-200 instead of 500) and
automatic recovery without a server restart.