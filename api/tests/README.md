# Backend tests

Strategy from plan §13.7 — no Laravel-style test harness; a layered suite that
catches the Webman-specific failure modes nothing else will.

## Layers

1. **Unit (majority)** — `tests/unit/`: pure services with plain PHPUnit, no
   framework boot: entitlement state machine, sync merge (last-write-wins +
   max(stars)), workout generator, rate-limit math. Run with `composer test`.

2. **Integration** — `tests/Integration/`: `tests/bootstrap.php` starts a real
   Webman instance on an ephemeral port in a child process, plus MySQL and
   Dragonfly containers; tests hit it over HTTP with Guzzle. Each test class
   gets its own Dragonfly db index via `SELECT` and a MySQL schema wrapped in
   a transaction that rolls back.

3. **Worker-longevity suite (do not skip)** — fires 10 000 requests through
   one worker and asserts (a) RSS growth < 5 MB, (b) no cross-request data
   bleed (request N never sees request N−1's user), (c) the worker is still
   alive. This is the test that catches §13.4.2 state-hygiene violations.

4. **Dragonfly command-surface test** — a `RedisCommandRecorder` decorator
   records every command the app issues during the integration suite; the
   `Compat/DragonflyCommandSurfaceTest` replays that list against a pinned
   Dragonfly container and fails on any error reply, so the verified command
   surface can't silently grow (§13.5).

5. **RTDN simulator** — replays recorded Google Play Pub/Sub payloads for
   every subscription transition, including out-of-order and duplicate
   delivery, against `POST /webhooks/play-rtdn`.

6. **Load** — k6 scenarios per route class, run nightly against staging, with
   the §13.6 capacity numbers (≥ 5 000 req/s cached reads, ≥ 800 req/s
   authenticated progress writes, p99 < 120 ms) as the pass threshold.

## Conventions

- Unit tests live next to their service under `tests/unit/` mirroring
  `app/service/`.
- `phpunit.xml.dist` at the repo root configures the `unit` suite; CI runs
  integration/load suites explicitly with the container stack.
- Anything touching money, entitlements, or published content must be tested
  through the `job_outbox` path (§13.4.5), not a direct queue push.