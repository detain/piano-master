# ADR-0002: Coroutine posture for HTTP workers and outbound fan-out

Status: Accepted
Date: 2026-08-22

## Context

Plan §13.4.3 requires a deliberate coroutine posture: the queue consumer will
fan out outbound HTTP (pipeline dispatch, CDN purge, email), and HTTP workers
serve request/response traffic. Workerman 5.2.2 in `vendor/` ships
`Workerman\Coroutine` with the **Fiber driver by default** (`Coroutine::init()`
maps the select/Fiber event loops to `Coroutine\Coroutine\Fiber`; Swoole/Swow
extensions are not installed, so the default is Fiber). The question is where
coroutines should be ON and where OFF, and the plan says: **coroutines OFF for
HTTP workers, ON for consumer-process outbound fan-out — backed by
measurements.** This ADR records the measurements and the decision.

### What was installed

- `workerman/coroutine` 1.1.6 and `webman/coroutine` 1.0.0 were already in
  `composer.lock` (the channel/WaitGroup/Barrier/Parallel primitives).
- `workerman/http-client` **v3.1.2** (async client, PSR-7, connection pool)
  was added for this ADR — the intended stack per §13.4.3. It requires
  `workerman/workerman ^5.1.11` (satisfied: 5.2.2) and `workerman/psr7`
  (added v2.0.2).
- `revolt/event-loop` **v1.0.9** was added: the Fiber event loop
  (`Workerman\Events\Fiber`) is Revolt-based, and coroutine-mode runs need it
  (the default select loop does not provide blocking channels). `config/server.php`
  now reads `event_loop` from env `WEBMAN_EVENT_LOOP` (default empty → the
  select loop; the HTTP-worker default is unchanged).

### Benchmark design

A Phase-0 spike endpoint `GET /bench/outbound?mode=blocking|fiber&fanout=N`
(`app/Controller/BenchController.php`, clearly commented as throwaway — it is
**kept** so the ADR numbers are re-runnable) fans out N requests to a local
mock upstream that sleeps 30 ms:

- `mode=blocking`: N **sequential** Guzzle requests in the worker thread.
- `mode=fiber`: N **concurrent** `Workerman\Coroutine` fibers using
  `workerman/http-client`, joined with `Workerman\Coroutine\Parallel`.

The mock upstream is a **threaded Python `ThreadingHTTPServer`** in
`/tmp/kq-mockup/` (not committed). The PHP built-in server is single-threaded
and serializes the sleeps, which would fake a "no win" result — this
methodology trap is why the upstream choice is stated explicitly. Upstream
latency 30 ms; the threaded server makes N concurrent client requests
complete in ~30 ms, not N × 30 ms.

Server under test: 4 workers pinned `taskset -c 0-3`, k6 pinned `taskset -c
4-7` (same methodology as `api/tests/load/README.md`). Fiber mode requires
`WEBMAN_EVENT_LOOP="Workerman\Events\Fiber" php start.php start`; blocking
mode runs on the default select loop (the production HTTP posture) and also
as a control on the fiber loop.

### A real bug found during the benchmark

The first fiber implementation joined with `WaitGroup` (channel-based). Under
concurrent load the fiber loop **intermittently deadlocked**: with 4
concurrent handlers × fanout 4, ~50% of runs completed only 3/4 handlers —
all upstream requests arrived and were answered (proven with upstream-side
logging), but the client never resumed the hung handler's fibers. Root cause
is the join mechanism: `Channel\Fiber::pop()` resumes the waiter
**synchronously** from inside the last fiber's `done()` call, which races
with the http-client's socket callbacks under the Fiber event loop.
`Workerman\Coroutine\Parallel` uses `Barrier`, whose Fiber driver **defers**
the waiter's resume to the next event-loop tick (`Barrier\Fiber` via
`Timer::delay`); 6/6 probe runs completed cleanly, including 8 handlers ×
fanout 8. **The spike endpoint therefore uses `Parallel`, and this finding is
recorded in the code** (`BenchController::fiberFanout()`). Any future
coroutine fan-out must use `Parallel`/`Barrier`, not `WaitGroup`+`Channel`,
on the Fiber loop.

## Options

- **O1 — Coroutines OFF everywhere (status quo).** HTTP workers stay on the
  select loop with blocking I/O; the consumer process also does blocking
  fan-out (sequential or per-process parallelism). Simplest; one mental model
  for state hygiene; but outbound fan-out is slow (N upstream calls ×
  latency).
- **O2 — Coroutines ON everywhere.** Set the Fiber event loop for every
  process. Great for fan-out, but HTTP workers pay event-loop complexity, the
  tail-latency profile shown below, and the state-bleed risk surface grows
  (§13.4.2: suspended fibers + shared statics + blocking I/O interleaving is
  exactly where the dangerous bugs live).
- **O3 — Coroutines OFF for HTTP workers, ON for consumer-process outbound
  fan-out (plan §13.4.3).** HTTP workers keep the simple synchronous model
  (proven by the load baseline: 31k req/s cached reads need no coroutines);
  the consumer process enables the Fiber event loop for its fan-out work and
  uses `Parallel`.

## Decision

**Adopt O3.** Coroutines are OFF for HTTP workers (select loop; the committed
`config/server.php` default), and ON for consumer-process outbound fan-out,
which will boot with `WEBMAN_EVENT_LOOP="Workerman\Events\Fiber"` and use
`Workerman\Coroutine\Parallel` for fan-out. The spike endpoint stays
(Phase-0 throwaway, clearly commented) so the numbers are re-runnable; the
mock upstream stays out of the repo (`/tmp/kq-mockup/`).

### Measurements (2026-08-22, 4 workers pinned, fanout=8, upstream 30 ms)

| mode | event loop | req/s | p50 | p90 | p95 | p99 | max | errors |
|---|---|---|---|---|---|---|---|---|
| blocking | select (HTTP default) | 15.95 | 496 ms | 989 ms | 996 ms | 1000 ms | 1.01 s | 0 |
| blocking | fiber (control) | 11.95 | 521 ms | 1.48 s | 1.48 s | 1.49 s | 1.50 s | 0 |
| **fiber** | **fiber** | **99.85** | **33 ms** | **34 ms** | **36 ms** | 1.08 s | 2.74 s | 2 (0.06 %) |

Single-request curl (no load): fiber fanout=8 ≈ **33 ms** (flat from fanout 1
to 8), blocking fanout=8 ≈ **247 ms** (linear — 8 × 30 ms).

### What the numbers say

- **For outbound fan-out, coroutines win decisively**: ~6.3x throughput
  (99.85 vs 15.95 req/s) and ~15x median latency (33 ms vs 496 ms). This is
  exactly the workload the consumer process has (pipeline dispatch, CDN
  purge, email — N outbound calls per job).
- **The HTTP worker's real workload does not fan out.** The cached-read load
  baseline (31 602 req/s, p99 10 ms) is one in-process Redis GET per request;
  coroutines add nothing there. The auth-write baseline is MySQL commit
  bound, not concurrency bound (see `api/tests/load/README.md`). There is no
  §13.6 workload on the HTTP worker that needs coroutines in P0/P1.
- **Fiber mode has a heavier tail** (p99 1.08 s, max 2.74 s vs blocking's
  uniform ~1 s) and a small error rate (0.06 % — connection churn in the
  http-client pool under sustained fan-out). For the consumer process this is
  acceptable (job retries exist); for the HTTP worker it is not.
- **Complexity/risk**: the WaitGroup+Channel deadlock above is a concrete
  example of the Fiber-loop foot-guns; keeping HTTP workers synchronous keeps
  the §13.4.2 state-bleed rules simple to reason about and enforce.

The measurements therefore **support the plan's expected decision**; if the
measurements had said otherwise (e.g. fiber mode being slower or
deadlock-free WaitGroup being fine), this ADR would say so — the decision is
based on the numbers, not the plan's default.

## Consequences

- **Positive:** HTTP workers keep the simple synchronous request model that
  the P0.6 load baseline and state-bleed guards are built around; the
  consumer process gets the 6.3x fan-out win when it lands (P1); the
  `Parallel`/`Barrier` join requirement is documented in code, so the P1
  consumer starts from the deadlock-free pattern.
- **Negative:** two event-loop modes exist in the codebase (select for HTTP,
  fiber opt-in for consumers) — a deploy must set `WEBMAN_EVENT_LOOP`
  correctly per process; the Fiber loop's tail latency means the consumer
  needs timeouts + retries (already planned); `revolt/event-loop`,
  `workerman/http-client`, `workerman/psr7` are new composer dependencies.
- **Dependencies added:** `workerman/http-client:^3.1`, `revolt/event-loop:^1.0`
  (+ transitive `workerman/psr7`). Both are required only for the
  consumer-process coroutine posture and the spike benchmark; HTTP workers
  never load them.
- **Follow-up (P1):** when the queue consumer lands, boot it with the Fiber
  event loop, use `Parallel` for fan-out, and add the `queue-drain.js` load
  scenario (see `api/tests/load/README.md` — N/A in P0.6).