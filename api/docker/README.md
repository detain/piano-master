# KeyQuest API — local dev services

P0.6.1 spike runtime dependencies: MySQL 8 + Dragonfly, pinned per
[`toolchain.md`](../../toolchain.md).

| Service    | Image | Port | Healthcheck |
|------------|-------|------|-------------|
| MySQL      | `mysql:8.0.43` | `3306` | `mysqladmin ping` |
| Dragonfly  | `ghcr.io/dragonflydb/dragonfly:v1.29.0` | `6379` | `redis-cli ping` |

## Commands

From `api/`:

```bash
# Start both services (detached), then verify they are healthy.
docker compose up -d
docker compose ps

# Boot the Webman API (default http://0.0.0.0:8787).
php start.php start

# Smoke-test the stack once both containers are healthy:
curl -s http://127.0.0.1:8787/             # static JSON
curl -s http://127.0.0.1:8787/healthz      # liveness
curl -s http://127.0.0.1:8787/readyz       # readiness (MySQL + Dragonfly ping)
curl -s http://127.0.0.1:8787/db/version   # MySQL read
curl -s http://127.0.0.1:8787/cache/now    # Dragonfly read-through cache
curl -s -X POST http://127.0.0.1:8787/auth/echo \
  -H 'Authorization: Bearer dev-token' \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello keyquest"}'        # authenticated DB write
```

## Notes

- **First boot:** the MySQL container runs `docker/mysql-init/01_schema.sql`
  only when its data volume is empty. Recreate the container to re-run the
  init: `docker compose down -v && docker compose up -d`.
- **Credentials:** root with an empty password (`MYSQL_ALLOW_EMPTY_PASSWORD`),
  database `keyquest`. The API reads these from env (`DB_HOST`, `DB_PORT`,
  `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`; `REDIS_HOST`, `REDIS_PORT`,
  `REDIS_PASSWORD`) with the compose defaults baked into `config/database.php`
  and `config/redis.php`.
- **Dev token:** `POST /auth/echo` accepts `Authorization: Bearer dev-token`
  (env `DEV_API_TOKEN`). This is a P0.6.1 placeholder — P2.A2 replaces it
  with RS256 JWT + rotating refresh tokens.
- **Stopping:** `docker compose stop` (keeps data) or `docker compose down`
  (removes containers).