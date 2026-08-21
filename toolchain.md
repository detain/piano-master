# KeyQuest toolchain — pinned versions

Per plan §20 P0.1.2: every version below is pinned in files, none in a wiki. CI and
laptops must build byte-identical artifacts; "works on my machine" is a bug report
against this table.

Status legend:

- **installed locally** — present on the reference dev machine (recon 2026-08-20)
- **CI-managed** — resolved by CI / lockfile / image tag at build time
- **install pending** — not yet installed; required before that workspace can build

| Component | Pinned version | Status | Notes |
|---|---|---|---|
| Android SDK (platform + build-tools) | latest stable, minSdk 26 | install pending | User is installing. |
| Kotlin | 2.2.0 | CI-managed (Gradle) | 2.x line per plan §3.2. KGP version pinned in `android/gradle/libs.versions.toml` (`kotlin = "2.2.0"`). |
| Android Gradle Plugin | 8.9.1 | CI-managed (Gradle) | First stable AGP line with full compileSdk 36 support; pairs with Gradle 8.11.1 and Kotlin 2.2.0 (pinned in `android/gradle/libs.versions.toml`). |
| Gradle | 8.11.1 | CI-managed (wrapper) | Wrapper checked into `android/` pins the distribution URL (`gradle-8.11.1-bin.zip`). |
| JDK | 21 | installed locally | Target for Kotlin/JVM compilation and CI. |
| CMake | 3.28+ | installed locally (3.28) | Minimum for `engine/`; CI pins a newer patch. |
| NDK | 27.3.13750724 (r27d) | installed locally | Installed via the SDK manager; used by the engine's Oboe audio build. |
| PHP | 8.3 | installed locally (8.3.6) | Runtime for `api/`. |
| Composer | 2.7 | installed locally (2.7.1) | `composer.lock` is committed; bootstrap runs `composer install --no-interaction`. |
| Node | 24 | installed locally | Runtime for `cms/` tooling. |
| npm | 11 | installed locally | Ships with Node 24. |
| Python | 3.12 | installed locally | Runtime for `pipeline/`. Deps declared in `pipeline/pyproject.toml`; `make bootstrap` installs the dev extras into `pipeline/.venv`. |
| ffmpeg | latest stable | installed locally | Audio stem processing in the pipeline; pinned by version in the CI image. |
| Webman | ^2.1 | CI-managed (Composer) | HTTP framework for `api/`; resolved by `composer.lock`. |
| Workerman | 5.1 | CI-managed (Composer) | Runtime underneath Webman; resolved by `composer.lock`. |
| MySQL | `mysql:8.0.43` | CI-managed (service container) | Pin the exact image tag (verify it exists at setup); used for `api-tests` CI job. |
| Dragonfly | `ghcr.io/dragonflydb/dragonfly:v1.29.0` | CI-managed (service container) | **Pin exactly** per plan §13.5 — treat any bump as a change that must pass `DragonflyCommandSurfaceTest`. Verify tag exists at setup (Docker Hub is stale; use GHCR). |
| Oboe | 1.9.x | CI-managed (Gradle dep) | Audio I/O for the engine; resolved by the dependency version catalog. |
| LiteRT / TFLite | 2.20.0 | CI-managed (Gradle dep) | On-device ML runtime; 2.1x line per plan §3.2. Verify exact artifact at setup. |