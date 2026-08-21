# KeyQuest API

REST JSON backend for the KeyQuest piano learning app. Webman 2.1 on Workerman
5.1 (long-lived worker processes — not PHP-FPM), MySQL 8 via
`webman/database` (Illuminate/Eloquent), Dragonfly (Redis-wire-compatible) for
cache/queue/ephemeral state. See `../plan_piano.md` §3.3 and §13.

## Stack

| Concern | Choice |
|---|---|
| Framework | Webman 2.1 (Workerman 5.1), `php start.php start` |
| DB | MySQL 8 (InnoDB) via `webman/database` (illuminate/database + Eloquent) |
| Cache / queue / rate-limit | Dragonfly via `webman/redis`, `webman/redis-queue` (§13.5) |
| Auth | `lcobucci/jwt` RS256 access tokens (15 min) + rotating refresh tokens (§3.3) |
| Validation | `respect/validation` |
| HTTP client | `guzzlehttp/guzzle` (Play receipt verify, CDN purge, JWKS refresh) |
| Tests | PHPUnit 11 (unit) + HTTP integration against booted Webman (§13.7) |

## Quick start

```bash
composer install              # first real install generates vendor/ + lockfile
php start.php start           # foreground; Ctrl+C stops
php start.php start -d        # daemonize
php start.php reload          # graceful reload (drains connections, zero dropped requests)
composer test                 # PHPUnit (tests/unit); integration needs containers (§13.7)
```

> Config files under `config/` are placeholders describing the planned
> inventory. The first real install (or webman console scaffolding) completes
> them — especially `app.php`, `route.php`, and the connection settings for
> `webman/database` and `webman/redis`.

## Architecture rules (plan §13.4.2 — the essentials)

Webman boots the app once and serves requests in a loop inside long-lived
workers. Violating these is the #1 Workerman bug class:

1. **Never `exit()`/`die()`** outside `start.php` — return a Response object.
   CI greps for `exit(`/`die(` in `app/`.
2. **No request data in statics** — no `static $user`, no container singleton
   holding `$request`, no `$GLOBALS`. Per-request values live in `$request` or
   a middleware-created context object, discarded at response time.
3. **Superglobals are meaningless** — use `$request->get()/post()/header()/
   session()`, never `$_GET/$_POST/$_SERVER/$_SESSION`.
4. **Process-global changes are permanent** — `set_error_handler`, `ini_set`,
   `date_default_timezone_set`, locale: bootstrap only, never per request.
5. **Anything registered in a loop leaks** — Eloquent listeners, middleware,
   autoloaders: bootstrap only (the Nth request must not fire N callbacks).
6. **In-process caches must be LRU** with a hard entry cap, never unbounded
   arrays.
7. **Long-lived DB connections drop on idle** — enable reconnect handling;
   keep MySQL `wait_timeout` above the idle window; verify with an
   "leave it overnight, then hit it" staging test.
8. **Single-instance processes** (`rtdn-consumer`, `workout-gen`,
   `analytics-flush`, `scheduler`, `monitor`) guard themselves with a
   Dragonfly lock (`SET lock:<name> <token> NX PX 30000` + refresh).

Jobs that touch money/entitlements/published content go through the MySQL
`job_outbox` inside the same transaction as the state change (§13.4.5).

## Process inventory

See `config/process.php` — `webman` (HTTP), `rtdn-consumer`, `queue-consumer`,
`workout-gen`, `analytics-flush`, `scheduler`, `monitor` (§13.4.1).

## Route map (stub — not yet implemented)

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register`, `/auth/login`, `/auth/google`, `/auth/refresh` | Auth (§13.2) |
| GET | `/me` | Current account |
| CRUD | `/me/profiles` | Up to 5 profiles |
| GET | `/catalog/courses`, `/catalog/songs` | Catalog with entitlements baked in |
| GET | `/catalog/songpacks/{id}/download-url` | Signed CDN URL, entitlement-checked |
| GET/PUT | `/profiles/{id}/progress` | Offline-first batch upsert, idempotent |
| POST | `/profiles/{id}/sessions` | Practice session + note stats |
| GET | `/profiles/{id}/workout?date=` | 5-Min Workout |
| POST | `/billing/play/verify` | Purchase token → entitlement |
| POST | `/webhooks/play-rtdn` | Google Play RTDN push |
| POST | `/support/tickets` | Support |
| GET | `/config` | Remote config: tunables, feature flags |
| GET | `/healthz`, `/readyz`, `/metrics` | Ops (§13.6) |

## Environment variables (no `.env` is committed)

Required at runtime (documented here; wire them via the deploy env, not a
committed file):

- `APP_ENV` — `dev` | `staging` | `prod`
- `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` (Dragonfly connection)
- `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` — RS256 keypair for access tokens
- `GOOGLE_CLIENT_ID` — Google Sign-In verification
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` — Play Developer API receipt verification