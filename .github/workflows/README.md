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
| `android-unit` | Android JVM unit tests pass (no emulator) | Final (wrapper committed) |
| `api-tests` | PHPUnit suite passes against MySQL + Dragonfly | Final (smoke test; real suite in P0.6) |
| `cms-build` | CMS production build succeeds | Final (lockfile committed, `npm ci`) |
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

- **Current:** the Gradle wrapper (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`) is committed and pins Gradle 8.11.1;
  the job runs `./gradlew testDebugUnitTest --no-daemon --stacktrace`. The
  version catalog (`libs.versions.toml`) pins dependency versions.
- **Fallback-only:** the `gradle` binary branch (installed by
  `gradle/actions/setup-gradle@v4`) remains for environments without the
  wrapper committed; it is not taken on the current tree.

Setup: temurin JDK 21 (`actions/setup-java@v4`, `cache: gradle`), Android SDK
via `android-actions/setup-android@v3` (`platform-tools`,
`platforms;android-36` — keep in sync with `/toolchain.md`, P0.1.2), and Gradle
user-home caching via `gradle/actions/setup-gradle@v4`. `--stacktrace` is
always passed: silent on green, gives a usable trace on red without a costly
second run.

### 3. `api-tests` — final

Gates the Webman 2.1 backend against real service containers:

- `mysql:8.0.43` — `MYSQL_ALLOW_EMPTY_PASSWORD=yes`, database `keyquest_test`,
  health-checked via `mysqladmin ping` (10s interval / 5s timeout / 10 retries),
  port 3306.
- `ghcr.io/dragonflydb/dragonfly:v1.29.0` — pinned per plan §13.5 (Dragonfly
  compatibility fence); matches `/toolchain.md`.

- **Current:** `phpunit.xml.dist` is committed and runs a smoke test
  (`tests/unit/SmokeTest.php`) proving the suite is wired; it fails on an
  empty suite (`failOnEmptyTestSuite="true"`).
- **Fallback-only:** the `composer validate --no-check-publish` branch remains
  for a tree without `phpunit.xml.dist`; it is not taken on the current tree.
- **Next:** the real PHPUnit suite (Webman + Dragonfly) lands with the P0.6
  spike and replaces the smoke test.

PHP 8.3 via `shivammathur/setup-php@v2` with extensions `pdo`, `pdo_mysql`,
`redis`, `mbstring`, `json` and `composer:v2`.

### 4. `cms-build` — final

Gates the Vue 3 + Vite + TypeScript production build.

- **Current:** `package-lock.json` is committed, so the job runs `npm ci`
  (reproducible and faster than install), then `npm run build` and
  `npm run lint`.
- **Fallback-only:** the `npm install --no-audit --no-fund` branch remains for
  a tree without the lockfile; it is not taken on the current tree.

setup-node's npm cache activates automatically only when the lockfile exists
via a `hashFiles` guard — it cannot fail the fallback path.

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
- Remaining fallback branches are fallback-only for pre-scaffold trees; the
  one `TODO(P0.1.2)` left is a sync reminder (platform level vs
  `/toolchain.md`), not a permanent gap — remove each branch/comment when the
  real artifact lands.

## Local verification

The workflow itself cannot run outside GitHub. Before pushing, validate
locally:

```sh
# YAML sanity (python3 ships on all runners; yq also works)
python3 -c 'import yaml,sys; yaml.safe_load(open(".github/workflows/ci.yml")); print("ci.yml OK")'
```