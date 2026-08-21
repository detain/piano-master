# KeyQuest CI — workflow documentation

Workflow: `.github/workflows/ci.yml` — five mandatory, parallel jobs (plan
§P0.1.3). Every job gates merge: **a PR that breaks any workspace is red before
review.**

## Budget

Total wall clock < 10 minutes. Jobs run in parallel on `ubuntu-latest`, and each
job is the cheapest thing that still proves its workspace builds and tests
green. Flakiness is fixed the day it appears, not triaged (plan §P0.1.3). Each
job has a 15-minute hard timeout as a runaway guard.

## Job table

| Job | Gates | Command state |
|-----|-------|---------------|
| `engine-host-tests` | C++20 core builds; `engine_host_tests` passes | Final |
| `android-unit` | Android JVM unit tests pass (no emulator) | Final |
| `api-tests` | PHPUnit suite passes against MySQL + Dragonfly | Final |
| `cms-build` | CMS production build succeeds | Final |
| `lint-all` | PHP syntax + §13.4.2 state-hygiene greps + Python compile + whitespace | Final |

## Job details

### 1. `engine-host-tests` — final

Gates the portable C++20 core (`engine_core` static lib) and its host test
executable:

```sh
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

No service containers, no SDK setup — plain `ubuntu-latest` runners already
carry cmake/g++. The `build/` dir is gitignored.

### 2. `android-unit` — final

Gates the Android app's JVM unit tests (`testDebugUnitTest`). No emulator, so
no device-flake surface.

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`)
is committed and pins Gradle 8.11.1; the job runs
`./gradlew testDebugUnitTest --no-daemon --stacktrace` directly (no
toolchain-gradle fallback). The version catalog (`libs.versions.toml`) pins
dependency versions.

Setup: temurin JDK 21 (`actions/setup-java@v5`, `cache: gradle`), Android SDK
via `android-actions/setup-android@v3` with packages
`platform-tools platforms;android-36` (space-separated — the action splits
`packages` on spaces and passes each token to sdkmanager as one package;
commas are NOT separators, so `platform-tools,platforms;android-36` would be
installed as a single bogus package; keep the API level in sync with
`/toolchain.md`, P0.1.2), and Gradle user-home caching via
`gradle/actions/setup-gradle@v4`.
`--stacktrace` is always passed: silent on green, gives a usable trace on red
without a costly second run.

### 3. `api-tests` — final

Gates the Webman 2.1 backend against real service containers:

- `mysql:8.0.43` — `MYSQL_ALLOW_EMPTY_PASSWORD=yes`, database `keyquest_test`,
  health-checked via `mysqladmin ping` (10s interval / 5s timeout / 10 retries),
  port 3306. The schema (`api/docker/mysql-init/01_schema.sql`, the same file
  the local stack uses) is applied by an explicit `Apply schema to test
  database` step via PHP PDO after checkout — NOT by a volume mount into
  `/docker-entrypoint-initdb.d`. A service container starts before checkout, so
  a workspace volume mount makes the docker daemon create the mount source
  (`api/docker`) as root in the still-empty workspace and `actions/checkout`'s
  clean step fails with `EACCES: permission denied, rmdir`. Without the schema
  `POST /auth/echo` fails with SQLSTATE 42S02.
- `ghcr.io/dragonflydb/dragonfly:v1.29.0` — pinned per plan §13.5 (Dragonfly
  compatibility fence); matches `/toolchain.md`.

- **Current:** `phpunit.xml.dist` is committed; the job runs
  `vendor/bin/phpunit --configuration phpunit.xml.dist` directly (no
  composer-validate fallback). It boots real Webman in a child process against
  the MySQL + Dragonfly service containers and fails on an empty suite
  (`failOnEmptyTestSuite="true"`).
- **DB env:** the MySQL service container creates only `keyquest_test`, but
  `api/config/database.php` defaults `DB_DATABASE` to `keyquest`. The
  `Run test suite` step therefore sets `DB_DATABASE=keyquest_test`,
  `DB_USERNAME=root`, `DB_PASSWORD=''` (root / empty password match
  `MYSQL_ALLOW_EMPTY_PASSWORD=yes`; they also match the config defaults).

PHP 8.3 via `shivammathur/setup-php@v2` with extensions `pdo`, `pdo_mysql`,
`redis`, `mbstring`, `json` and `composer:v2`.

### 4. `cms-build` — final

Gates the Vue 3 + Vite + TypeScript production build.

- **Current:** `package-lock.json` is committed, so the job runs `npm ci`
  (reproducible and faster than install), then `npm run build` and
  `npm run lint`.

setup-node is pinned to Node 24 (matches `/toolchain.md` and the
ubuntu-24.04 runner default; avoids setup-node's Node 20 deprecation warning)
with `cache: npm` and `cache-dependency-path: cms/package-lock.json` — the
cache points at the committed lockfile instead of searching the repo root.

### 5. `lint-all` — final

Gates the plan's Webman state-hygiene rules (§13.4.2) from day one, before
there is code to violate them (P2.A1), plus cheap syntax/format sanity across
workspaces. Each check is its own step with `set -euxo pipefail`; any match
fails the job:

1. **PHP syntax:** `php -l` over every non-vendor `.php` file under `api/`.
2. **No `exit()`/`die()`** in `api/app`, `api/config`, `api/support` — it kills
   the Workerman worker, not the request. `start.php` is outside the scanned
   dirs, so bootstrap exits stay legal.
3. **No superglobal reads** (`$_GET/$_POST/$_SERVER/$_SESSION/$_REQUEST/
   $_COOKIE/$_FILES/$_ENV`) in `api/app`, `api/config` — superglobals are not
   populated per request in long-lived workers; use `$request->get()/post()/
   header()/session()`.
4. **Python compile:** `python -m py_compile` over `pipeline/` (guarded for an
   empty file list).
5. **Trailing whitespace:** `grep -rn '[[:blank:]]$'` over engine/android/api/
   cms/pipeline source files, excluding vendor, node_modules, build, .gradle,
   dist, __pycache__. This is the deterministic stand-in for
   `editorconfig-checker` (no npx downloads, same signal, zero flake).

## Intent (from plan §P0.1.3)

- A PR that breaks any workspace is red before review.
- No flaky job is tolerated: flakiness gets fixed the day it appears, not
  triaged.
- Pinning (plan §P0.1.2): tool versions and image tags live in `/toolchain.md`;
  every place CI must follow it carries a `TODO(P0.1.2)` comment.
- No degraded fallback branches remain: every job runs its final check. The
  one `TODO(P0.1.2)` left is a sync reminder (platform level vs
  `/toolchain.md`), not a permanent gap.

## Local verification

The workflow itself cannot run outside GitHub. Before pushing, validate
locally:

```sh
# YAML sanity (python3 ships on all runners; yq also works)
python3 -c 'import yaml,sys; yaml.safe_load(open(".github/workflows/ci.yml")); print("ci.yml OK")'
```