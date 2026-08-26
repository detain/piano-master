# P0.6.3 8-hour idle soak — 2026-08-26

Manual backend soak per `api/tests/Integration/SoakDrill.md` §1 (P0.6.3): the
idle-overnight second half of the plan's longevity soak — leave the SAME worker
idle for 8 h, then hit it again — plus the post-idle documented-command soak and
the deterministic reconnect check (§1e/§1f of the drill). Closes the P0.6.3 idle
requirement that was previously gated on the 8 h wait.

## 0. Environment

| Item | Value |
|---|---|
| API HEAD (`git rev-parse HEAD` in `/api`) | `2af30e8` (master) |
| Monorepo HEAD | `2af30e8` |
| MySQL image | `mysql:8.0.43` (container `keyquest-mysql`, host-network, `127.0.0.1:3306`) |
| Dragonfly image | `ghcr.io/dragonflydb/dragonfly:v1.29.0` (container `keyquest-dragonfly`, host-network, `127.0.0.1:6379`) |
| API process | `php start.php start`, `WEBMAN_LISTEN=http://127.0.0.1:8787`, `count=1` (config/server.php default) |
| API process PID | `2235980` (unchanged for the whole session, 12:03:57Z → 20:06Z) |
| `docker compose up` | **NOT used** — the Docker bridge is broken on this box; containers run with `docker run --network host` (verified via `docker inspect`: `NetworkMode=host`) |

## 1. The idle phase

The worker was left idle **8 h (28 800 s): 12:03:57Z → 20:03:57Z** with **zero
traffic** — the webman API, MySQL, and Dragonfly were all untouched for the
whole window. The API process PID **2235980 stayed alive** throughout (no
restart; checked against `runtime/webman.pid`).

## 2. The post-idle soak

After the idle window, the same process was hit with the documented soak
command — the P0.6.3 requirement (SoakDrill.md §1b). `--total=5000
--duration=120` are the drill's own defaults; with the pacing fix (43ee9a1)
`--duration` is a rate limiter, so the 120 s deadline governs at ~42 rps:

```bash
php tests/scripts/soak.php --total=5000 --duration=120 --concurrency=16
```

- Total requests: **5000** — elapsed **120 s** — **~41.7 req/s**
- Errors: **0** across both per-minute buckets
- `bleed_mismatches`: **0**
- RSS first/last: **40372 / 40372 kB** (growth **0 kB**)
- Worker alive at end: **PASS** (PID 2235980 unchanged)
- Summary JSON: `/tmp/kq-soak-summary-2999647.json` (driver default path)

Per-minute table:

| minute | count | err | p50 ms | p95 ms | p99 ms | rss kB |
|---|---|---|---|---|---|---|
| 1 | 2485 | 0 | 5.93 | 993.57 | 995.03 | 40372 |
| 2 | 2515 | 0 | 5.93 | 993.52 | 994.89 | 40372 |

## 3. Reconnect check

Run **before** the soak on the same process, per SoakDrill.md §1f: the worker's
live MySQL connection was killed server-side (1 connection) and the FIRST
request afterwards was `GET /db/version`:

```bash
php tests/scripts/soak.php --base=http://127.0.0.1:8787 --reconnect-check
```

- First request after the kill → **HTTP 200** (`{"db_version":"8.0.43"}`) — no
  "MySQL server has gone away"; the reconnect fix (43ee9a1) holds on the
  immediate path.
- Verdict: **PASS** (`mysql_reconnect_after_kill`)

## 4. Verdicts

| Check | Result |
|---|---|
| `rss_growth_lt_5mb` | PASS (0 kB growth — 40372 kB before/after) |
| `p99_last_within_10pct` | PASS (995.03 → 994.89 ms, ratio 0.99986) |
| `zero_bleed_mismatches` | PASS (0 across 5000 requests) |
| `worker_alive_at_end` | PASS (PID 2235980 unchanged) |
| `mysql_reconnect_after_kill` | PASS (standalone check; first request 200) |

Summary JSON: `/tmp/kq-soak-summary-2999647.json`.

## 5. Notes

- The 8 h idle produced **zero drift**: RSS flat at 40372 kB before and after,
  no restarts, no errors on the post-idle hit. The 2026-08-22 runbook covered
  only a ~25-minute idle window; this run satisfies the full idle-overnight
  expectation of plan §20 P0.6.3 ("leave it idle overnight and hit it again").
- MySQL `wait_timeout` is 28800 s (8 h), so the idle window sits exactly at the
  server-side close boundary; the deterministic reconnect check (§3) exercises
  the reconnect path regardless of whether MySQL closed the connection.
- The soak-driver verdict set (`rss_growth_lt_5mb`, `p99_last_within_10pct`,
  `zero_bleed_mismatches`, `worker_alive_at_end`) plus the standalone
  `mysql_reconnect_after_kill` check all PASS.
- API process left running for follow-up work; no code changes were made.