# Reload & failover drills — P0.6.6 (plan §20 P0.6.6)

Executed 2026-08-22 against commit `P0.6-C`. Server: 4 workers
(`WEBMAN_COUNT=4`), pinned `taskset -c 0-3`, select event loop (production
default), request logging ON. k6 pinned to CPUs 4–7. MySQL 8.0.43 +
Dragonfly v1.29.0 as host-network containers.

The P0.6.3 soak / P0.6.4 Dragonfly-restart manual procedures remain
documented in `tests/Integration/SoakDrill.md`; those are the hour-long
versions. These drills are the k6-under-load versions required by P0.6.6.

## Drill A — graceful reload under load

**Goal**: `php start.php reload` mid-run must not drop requests, 502, or take
the server down.

**Procedure**:
1. Start server (above), wait for `/healthz` 200.
2. `taskset -c 4-7 ~/bin/k6 run -e VUS=50 -e DURATION=30s catalog-reads.js`.
3. At ~12 s into the run: `cd api && php start.php reload`.
4. Probe `/healthz` + `/cache/now` during the reload window.
5. Read k6 summary; verify server still up (`php start.php status`).

**Results**:

| metric | value |
|---|---|
| total requests | 2 047 033 |
| checks failed / error rate | **0** / **0.00%** |
| req/s | 34 116 |
| avg / p95 / p99 | 1.05 ms / 2.11 ms / 3.03 ms |
| max | 17.88 ms |
| server after reload | **up** — `healthz` 200, 4 workers running |
| curl during reload window | `/healthz` 200 (0.7 ms), `/cache/now` 200 (2.1 ms) |

**Verdict**: PASS. Zero failed requests, zero dropped connections, no 502s.
Workerman's master keeps the accept socket while workers drain/restart, so
in-flight and new connections are served without a gap; the aggregate p99
(3.03 ms) shows no reload blip. (A single reload was exercised; the plan's
multi-reload rolling deployment procedure is the P1 concern.)

## Drill B — Dragonfly kill / failover with graceful degradation

**Goal**: killing Dragonfly mid-load must NOT 500. `/cache/now` degrades to
a 200 with `degraded: true` (CacheGuard fallback), `/readyz` honestly reports
`503 not_ready`, non-cache routes keep serving, and restarting the container
recovers automatically (no server restart).

**Procedure**:
1. Start server; `docker ps` shows `keyquest-dragonfly` healthy.
2. `~/bin/k6 run -e VUS=40 -e DURATION=50s catalog-reads.js` (40 VUs, hold
   50 s + 30 s ramp).
3. At ~42 s: `docker kill keyquest-dragonfly`.
4. During the outage: curl `/cache/now`, `/readyz`, `/`, `/db/version`.
5. At ~77 s: `docker start keyquest-dragonfly`; wait for healthy.
6. Immediately curl `/cache/now` + `/readyz` (recovery check).
7. Read k6 summary — the whole run (normal → outage → recovery) is one
   measurement window.

**Results**:

| phase | observation |
|---|---|
| pre-kill | `/cache/now` normal (`from_cache: true`) |
| outage sample | `/cache/now` → 200 `{"degraded":true,"from_cache":false,...}` |
| outage sample | `/readyz` → **503** `{"status":"not_ready","dragonfly":false}` |
| outage sample | `/`, `/healthz`, `/db/version` → **200** |
| recovery (~5 s after `docker start`) | `/cache/now` → 200 normal (`from_cache: true`, no `degraded`) |
| recovery | `/readyz` → **200** ready |

**k6 across the whole window (normal → outage → recovery under load)**:

| metric | value |
|---|---|
| total requests | 1 831 768 |
| checks failed / error rate | **0** / **0.00%** |
| req/s | 22 896 |
| avg / p95 / p99 | 1.34 ms / 2.69 ms / 3.74 ms |
| max | 28.88 ms (the outage-transition blip) |

A second run (kill at 18 s, restart after the run) also recorded
**2 600 813 requests / 0 errors / p99 4.39 ms** through the outage window.

**Verdict**: PASS. The API never 500'd. The degradation path (`CacheGuard`
+ `CacheController`) returned degraded-but-successful 200s for the whole
outage; readiness correctly flipped to 503 (that is the desired behavior —
orchestrators pull traffic from a not-ready instance); recovery was automatic
and fast (next cache-miss re-seeded `kq:boot_time`). CacheGuard logged
exactly one structured `cache_guard.degraded` warning per 5 s throttle window
per worker (process-static timestamp — §13.4.2-compliant process state; see
`app/Support/CacheGuard.php` and the StateBleedGuardTest whitelist).

## Automated regression for the degradation path

`tests/Integration/CacheDegradationTest.php` boots a real Webman child with
`REDIS_PORT` pointed at a closed port (Dragonfly unreachable by construction)
and asserts: `/cache/now` → 200 `degraded: true` (twice — the pool must not
wedge), `/readyz` → 503 `dragonfly:false`, `/` + `/healthz` + `/db/version`
→ 200. It is in the default integration suite and passed with the full suite
(21 tests / 106 assertions) on this commit.

## Notes for the phase gate

- Drill B's outage window produced ~28 ms max latency (the transition), then
  steady degraded latency equal to the fallback computation — no 2 s
  read-timeout pile-up, because a killed container releases the port
  (connection refused is immediate). A hung-but-bound server (unreachable
  firewall drop, not this drill) would exercise the 2 s `read_timeout`
  instead; the guard bounds that too.
- The drills ran the SELECT (production-default) event loop; the degraded
  path is loop-agnostic.