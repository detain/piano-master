# KeyQuest root Makefile
#
# Developer entry points (all idempotent — safe to re-run):
#   make bootstrap   install every workspace's dependencies, print per-workspace status
#   make test        run each workspace's checks (ctest / gradle unit tests / phpunit / cms build / py_compile)
#   make lint        run the lint-all CI checks (php -l, py_compile, plan §13.4.2 greps)
#   make provision   install the full server toolchain (scripts/provision-server.sh; needs sudo)
#
# Workspaces that are not yet present are skipped with a status line, so this
# Makefile works on a fresh clone before every workspace has been scaffolded.

SHELL := /bin/bash

# Android SDK location — used by `make test`'s android branch. Defaults to the
# standard SDK-manager path; override with `make ANDROID_HOME=/path`.
ANDROID_HOME ?= $${HOME}/Android/Sdk

.PHONY: bootstrap test lint provision

bootstrap:
	@set -e; \
	echo "==> KeyQuest bootstrap"; \
	echo ""; \
	\
	if [ -f engine/CMakeLists.txt ]; then \
		echo "==> engine: configure CMake (Debug)"; \
		cmake -S engine -B engine/build -DCMAKE_BUILD_TYPE=Debug >/dev/null; \
		echo "    engine: OK - host tests run via 'make test'"; \
	else \
		echo "--> engine: skipped (not present)"; \
	fi; \
	\
	if [ -d android ]; then \
		echo "==> android: sync Gradle (uses ANDROID_HOME if set, else $${HOME}/Android/Sdk)"; \
		if [ -x android/gradlew ]; then \
			(cd android && ./gradlew --version) >/dev/null; \
		fi; \
		echo "    android: OK"; \
	else \
		echo "--> android: skipped (not present)"; \
	fi; \
	\
	if [ -f api/composer.json ]; then \
		echo "==> api: composer install"; \
		(cd api && composer install --no-interaction) >/dev/null; \
		echo "    api: OK"; \
	else \
		echo "--> api: skipped (not present)"; \
	fi; \
	\
	if [ -f cms/package.json ]; then \
		echo "==> cms: npm install"; \
		(cd cms && npm install) >/dev/null; \
		echo "    cms: OK"; \
	else \
		echo "--> cms: skipped (not present)"; \
	fi; \
	\
	if [ -f pipeline/pyproject.toml ]; then \
		echo "==> pipeline: python venv + deps (editable install, dev extras)"; \
		python3 -m venv pipeline/.venv; \
		pipeline/.venv/bin/pip install --quiet -e "pipeline[dev]"; \
		echo "    pipeline: OK"; \
	else \
		echo "--> pipeline: skipped (not present)"; \
	fi; \
	\
	echo "==> content/docs: no dependencies (data + markdown)"; \
	echo ""; \
	echo "==> bootstrap complete"

test:
	@set -e; \
	echo "==> KeyQuest test suite"; \
	\
	if [ -f engine/CMakeLists.txt ]; then \
		echo "==> engine: host tests (ctest)"; \
		cmake -S engine -B engine/build -DCMAKE_BUILD_TYPE=Debug >/dev/null; \
		cmake --build engine/build >/dev/null; \
		(cd engine/build && ctest --output-on-failure); \
	else \
		echo "--> engine: skipped (not present)"; \
	fi; \
	\
	if [ -f android/settings.gradle.kts ] && [ -x android/gradlew ]; then \
		echo "==> android: unit tests (testDebugUnitTest)"; \
		(cd android && ANDROID_HOME="$(ANDROID_HOME)" ./gradlew testDebugUnitTest --no-daemon); \
	else \
		echo "--> android: skipped (not present)"; \
	fi; \
	\
	if [ -x api/vendor/bin/phpunit ]; then \
		echo "==> api: phpunit"; \
		(cd api && vendor/bin/phpunit); \
	else \
		echo "--> api: skipped (composer install not run yet)"; \
	fi; \
	\
	if [ -f cms/package.json ]; then \
		echo "==> cms: build"; \
		(cd cms && npm run build); \
	else \
		echo "--> cms: skipped (not present)"; \
	fi; \
	\
	if [ -d pipeline ]; then \
		echo "==> pipeline: py_compile"; \
		find pipeline -name '*.py' -exec python3 -m py_compile {} +; \
	else \
		echo "--> pipeline: skipped (not present)"; \
	fi; \
	\
	echo "==> test complete"

lint:
	@set -e; \
	echo "==> KeyQuest lint (lint-all CI checks)"; \
	fail=0; \
	\
	if [ -d api ]; then \
		echo "==> api: php -l"; \
		find api -name '*.php' -not -path '*/vendor/*' -exec php -l {} >/dev/null \; || fail=1; \
	else \
		echo "--> api: skipped (not present)"; \
	fi; \
	\
	if [ -d pipeline ]; then \
		echo "==> pipeline: py_compile"; \
		find pipeline -name '*.py' -exec python3 -m py_compile {} + || fail=1; \
	else \
		echo "--> pipeline: skipped (not present)"; \
	fi; \
	\
	if [ -d api ]; then \
		echo "==> api: plan §13.4.2 grep - exit()/die() outside start.php"; \
		hits=$$(grep -rnE '\b(exit|die)[[:space:]]*\(' api --include='*.php' --exclude-dir=vendor \
			| grep -v '^api/start\.php' || true); \
		if [ -n "$$hits" ]; then \
			echo "!! forbidden exit()/die() outside api/start.php:"; \
			printf '%s\n' "$$hits"; \
			fail=1; \
		fi; \
		if [ -d api/app ]; then \
			echo "==> api: plan §13.4.2 grep - superglobals in api/app"; \
			hits=$$(grep -rnE '\$$_(GET|POST|SERVER|SESSION|REQUEST|COOKIE|FILES|ENV)' api/app || true); \
			if [ -n "$$hits" ]; then \
				echo "!! forbidden superglobal read in api/app:"; \
				printf '%s\n' "$$hits"; \
				fail=1; \
			fi; \
		fi; \
	else \
		echo "--> api: §13.4.2 greps skipped (api not present)"; \
	fi; \
	\
	if [ "$$fail" -ne 0 ]; then \
		echo "!! lint failed"; \
		exit 1; \
	fi; \
	echo "==> lint OK"

provision:
	@echo "==> KeyQuest server provisioning (OS-level steps need sudo)"
	@./scripts/provision-server.sh