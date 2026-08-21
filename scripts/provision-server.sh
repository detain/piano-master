#!/usr/bin/env bash
# KeyQuest — dev-environment server provisioner.
#
# Bootstraps a fresh Ubuntu 24.04 (or Debian 12+) server so that a fresh clone
# plus `make bootstrap` produces a working dev environment (plan §20 P0.1.1).
# Every step is idempotent: safe to re-run, and each step skips work already
# done. Pinned versions mirror toolchain.md (plan §20 P0.1.2) — keep in sync.
#
# Flags:
#   --check          Read-only: print installed versions vs pinned, then exit.
#   --skip-system    Skip OS-level installs (apt/docker/node/composer); only
#                    run `make bootstrap`.
#   --with-android   Also install the Android SDK and accept its licenses.
#   --skip-android   Override --with-android (Android is OFF by default).
#   -h, --help       Show usage.
#
# Android SDK is OFF by default because its licenses require user acceptance.

set -euo pipefail

# --- Paths ------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "${SCRIPT_DIR}")"

# --- Pinned versions (toolchain.md) -----------------------------------------

PIN_PHP="8.3"
PIN_COMPOSER="2"       # major line
PIN_NODE="24"
PIN_NPM="11"
PIN_PYTHON="3.12"
PIN_CMAKE="3.28"
PIN_GPP="13"
PIN_JAVA="21"
NODE_MIN_EXISTING="20.19"  # skip NodeSource if an existing node is >= this

# --- Android SDK (optional) -------------------------------------------------

ANDROID_HOME_DEFAULT="${HOME}/Android/Sdk"
# Cmdline-tools URL is versioned by Google; bump when a newer stable exists.
ANDROID_CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
ANDROID_PACKAGES=(platform-tools "platforms;android-36" "build-tools;36.0.0" "ndk;27.3.13750724")

# --- Global state -----------------------------------------------------------

MODE="provision"
SKIP_SYSTEM=0
WITH_ANDROID=0
DISTRO_ID=""
DISTRO_VERSION_ID=""
DISTRO_PRETTY=""

if [ "$(id -u)" -eq 0 ]; then SUDO_CMD=""; else SUDO_CMD="sudo"; fi
USER_NAME="${USER:-$(id -un)}"

# --- Helpers ----------------------------------------------------------------

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# version_ge A B — true when dotted numeric A >= B (e.g. "24.04" >= "24").
version_ge() {
    local a_str="${1#v}" b_str="${2#v}"
    local -a a b
    IFS='.' read -r -a a <<< "${a_str}"
    IFS='.' read -r -a b <<< "${b_str}"
    local i x y
    for (( i = 0; i < ${#a[@]} || i < ${#b[@]}; i++ )); do
        x="${a[$i]:-0}"; y="${b[$i]:-0}"
        x="${x#0}"; y="${y#0}"
        x="${x:-0}"; y="${y:-0}"
        if (( 10#$x > 10#$y )); then return 0; fi
        if (( 10#$x < 10#$y )); then return 1; fi
    done
    return 0
}

usage() {
    cat <<'EOF'
Usage: ./scripts/provision-server.sh [flags]

Bootstraps a fresh Ubuntu 24.04 / Debian 12+ server with the KeyQuest dev
toolchain (plan §20 P0.1.1), then runs `make bootstrap`.

Flags:
  --check          Read-only: print installed tool versions vs toolchain.md.
  --skip-system    Skip OS-level installs (apt, docker, node, composer); only
                   run `make bootstrap`.
  --with-android   Also install the Android SDK (cmdline-tools, platform 36,
                   build-tools 36.0.0, NDK r27d) and accept its licenses.
  --skip-android   Override --with-android (Android is OFF by default).
  -h, --help       Show this help.

Notes:
  - Idempotent: safe to re-run; each step skips work already done.
  - OS-level installs need sudo; the invoking user is added to the docker group.
  - Android SDK is OFF by default because SDK licenses require user acceptance.
  - Debian 12 is supported with caveats (see distro warnings).
EOF
}

# --- Tool discovery ---------------------------------------------------------

# tool_version NAME — prints the installed version of NAME (empty if missing).
# Every branch pipes through awk/sed so a missing binary cannot abort set -e.
tool_version() {
    case "$1" in
        git)        git --version 2>/dev/null | awk '{print $3}' ;;
        curl)       curl --version 2>/dev/null | awk 'NR==1{print $2}' ;;
        make)       make --version 2>/dev/null | awk 'NR==1{print $NF}' ;;
        php)        php -r 'echo PHP_VERSION;' 2>/dev/null || true ;;
        composer)   composer --version 2>/dev/null | awk '{print $3}' ;;
        node)       node -v 2>/dev/null | tr -d 'v' ;;
        npm)        npm -v 2>/dev/null ;;
        python3)    python3 --version 2>/dev/null | awk '{print $2}' ;;
        cmake)      cmake --version 2>/dev/null | awk '/cmake version/{print $3; exit}' ;;
        g++)        g++ -dumpfullversion 2>/dev/null ;;
        ninja)      ninja --version 2>/dev/null ;;
        pkg-config) pkg-config --version 2>/dev/null ;;
        ffmpeg)     ffmpeg -version 2>/dev/null | awk 'NR==1{print $3}' ;;
        java)       java -version 2>&1 | sed -n 's/.*"\([0-9][0-9.]*\)".*/\1/p' | head -1 ;;
        docker)     docker --version 2>/dev/null | awk '{print $3}' | tr -d ',' ;;
        compose)
            if docker compose version >/dev/null 2>&1; then
                docker compose version 2>/dev/null | awk '{print $NF}' | tr -d 'v'
            elif command -v docker-compose >/dev/null 2>&1; then
                docker-compose --version 2>/dev/null | awk '{print $3}' | tr -d ','
            fi
            ;;
        *) echo "" ;;
    esac
}

# Tools to verify: NAME:MODE:PINNED. Modes: min (>=), major (x line), present.
CHECK_LIST=(
    "git:present:present"
    "curl:present:present"
    "make:present:present"
    "php:min:${PIN_PHP}"
    "composer:major:${PIN_COMPOSER}"
    "node:min:${PIN_NODE}"
    "npm:min:${PIN_NPM}"
    "python3:min:${PIN_PYTHON}"
    "cmake:min:${PIN_CMAKE}"
    "g++:min:${PIN_GPP}"
    "ninja:present:present"
    "pkg-config:present:present"
    "ffmpeg:present:present"
    "java:min:${PIN_JAVA}"
    "docker:present:present"
    "compose:present:present"
)

# check_tool NAME MODE PINNED — prints one table row with version and status.
check_tool() {
    local name="$1" mode="$2" pinned="$3"
    local version status pinned_disp
    version="$(tool_version "$name")"
    case "$mode" in
        min)
            if [ -z "$version" ]; then
                status="MISSING"
            elif version_ge "$version" "$pinned"; then
                status="OK"
            else
                status="OUTDATED"
            fi
            pinned_disp=">= ${pinned}"
            ;;
        major)
            if [ -z "$version" ]; then
                status="MISSING"
            elif [ "${version%%.*}" = "$pinned" ]; then
                status="OK"
            else
                status="OUTDATED"
            fi
            pinned_disp="${pinned}.x"
            ;;
        present)
            if [ -z "$version" ]; then
                status="MISSING"
            else
                status="OK"
            fi
            pinned_disp="any"
            ;;
    esac
    printf '%-14s %-18s %-10s %s\n' "$name" "${version:--}" "$pinned_disp" "$status"
}

print_table() {
    printf '%-14s %-18s %-10s %s\n' "Tool" "Version" "Pinned" "Status"
    printf '%-14s %-18s %-10s %s\n' "----" "-------" "------" "------"
    local entry name mode pinned
    for entry in "${CHECK_LIST[@]}"; do
        IFS=':' read -r name mode pinned <<< "${entry}"
        check_tool "$name" "$mode" "$pinned"
    done
}

# --- Distro detection -------------------------------------------------------

detect_distro() {
    if [ ! -r /etc/os-release ]; then
        warn "cannot read /etc/os-release — skipping distro check"
        return
    fi
    # shellcheck disable=SC1091
    . /etc/os-release
    DISTRO_ID="${ID:-unknown}"
    DISTRO_VERSION_ID="${VERSION_ID:-0}"
    DISTRO_PRETTY="${PRETTY_NAME:-${DISTRO_ID} ${DISTRO_VERSION_ID}}"

    case "$DISTRO_ID" in
        ubuntu)
            if ! version_ge "$DISTRO_VERSION_ID" "24.04"; then
                warn "detected ${DISTRO_PRETTY}; target is Ubuntu 24.04 — continuing anyway"
            fi
            ;;
        debian)
            if ! version_ge "$DISTRO_VERSION_ID" "12"; then
                warn "detected ${DISTRO_PRETTY}; target is Debian 12+ — continuing anyway"
            fi
            warn "Debian note: php8.3-* packages need the Sury repository; the compose plugin may be named 'docker-compose-v2' or live in bookworm-backports."
            ;;
        *)
            warn "detected '${DISTRO_PRETTY}'; script targets Ubuntu 24.04 / Debian 12+ — continuing anyway"
            ;;
    esac
}

# --- Provisioning steps -----------------------------------------------------

install_system_packages() {
    log "apt-get update"
    $SUDO_CMD apt-get update

    # docker-compose-plugin is the Ubuntu 24.04 name; docker-compose-v2 is the
    # alternative on some Debian releases.
    local compose_pkg="docker-compose-plugin"
    if ! apt-cache show docker-compose-plugin >/dev/null 2>&1; then
        compose_pkg="docker-compose-v2"
    fi

    local pkgs=(
        git curl ca-certificates build-essential cmake ninja-build pkg-config
        ffmpeg python3 python3-venv python3-pip openjdk-21-jdk-headless
        php8.3-cli php8.3-mbstring php8.3-xml php8.3-curl php8.3-mysql
        php8.3-redis php8.3-zip composer nodejs npm docker.io unzip "${compose_pkg}"
    )
    log "installing system packages (${#pkgs[@]}):"
    printf '    %s\n' "${pkgs[@]}"
    # apt-get install is itself idempotent: already-installed packages are kept.
    $SUDO_CMD apt-get install -y "${pkgs[@]}"
}

setup_docker() {
    log "enabling and starting docker"
    if ! $SUDO_CMD systemctl enable --now docker 2>/dev/null; then
        $SUDO_CMD service docker start 2>/dev/null \
            || die "could not start docker (systemctl and service both failed)"
    fi

    if id -nG "$USER_NAME" | tr ' ' '\n' | grep -qx docker; then
        log "user '${USER_NAME}' is already in the docker group"
    else
        $SUDO_CMD usermod -aG docker "$USER_NAME"
        warn "added '${USER_NAME}' to the docker group — re-login or run 'newgrp docker' before using docker"
    fi
}

install_node() {
    local existing
    existing="$(node -v 2>/dev/null | tr -d 'v' || true)"
    if [ -n "$existing" ] && version_ge "$existing" "$NODE_MIN_EXISTING"; then
        log "node v${existing} present (>= ${NODE_MIN_EXISTING}); skipping NodeSource install"
        if ! version_ge "$existing" "$PIN_NODE"; then
            warn "node v${existing} is below pinned v${PIN_NODE} — cms tooling may need Node ${PIN_NODE}"
        fi
        return
    fi

    log "installing Node ${PIN_NODE} via NodeSource"
    if [ -n "$SUDO_CMD" ]; then
        curl -fsSL "https://deb.nodesource.com/setup_${PIN_NODE}.x" | $SUDO_CMD -E bash -
    else
        curl -fsSL "https://deb.nodesource.com/setup_${PIN_NODE}.x" | bash -
    fi
    $SUDO_CMD apt-get install -y nodejs
}

install_composer() {
    if command -v composer >/dev/null 2>&1; then
        log "composer $(composer --version 2>/dev/null | awk '{print $3}') present; skipping official installer"
        return
    fi

    log "installing composer via the official installer (to /usr/local/bin)"
    if [ -n "$SUDO_CMD" ]; then
        curl -fsSL https://getcomposer.org/installer | $SUDO_CMD php -- --install-dir=/usr/local/bin --filename=composer
    else
        curl -fsSL https://getcomposer.org/installer | php -- --install-dir=/usr/local/bin --filename=composer
    fi
}

install_android_sdk() {
    local sdk_root="${ANDROID_HOME:-${ANDROID_HOME_DEFAULT}}"
    local sdkmanager="${sdk_root}/cmdline-tools/latest/bin/sdkmanager"
    local zip_tmp="${TMPDIR:-/tmp}/keyquest-android-cmdline-tools.zip"

    if [ -x "$sdkmanager" ]; then
        log "Android cmdline-tools already present at ${sdk_root}; skipping download"
    else
        log "downloading Android cmdline-tools to ${sdk_root}"
        mkdir -p "${sdk_root}/cmdline-tools"
        curl -fsSL -o "$zip_tmp" "$ANDROID_CMDLINE_URL"
        unzip -q "$zip_tmp" -d "${sdk_root}/cmdline-tools"
        mv "${sdk_root}/cmdline-tools/cmdline-tools" "${sdk_root}/cmdline-tools/latest"
        rm -f "$zip_tmp"
    fi

    log "accepting Android SDK licenses"
    yes | "$sdkmanager" --licenses >/dev/null

    log "installing Android SDK packages: ${ANDROID_PACKAGES[*]}"
    "$sdkmanager" "${ANDROID_PACKAGES[@]}"

    if grep -q '^export ANDROID_HOME=' "${HOME}/.bashrc" 2>/dev/null; then
        log "ANDROID_HOME already exported in ~/.bashrc"
    else
        {
            echo ""
            echo "# KeyQuest: Android SDK (provision-server.sh)"
            echo "export ANDROID_HOME=${sdk_root}"
            echo "export ANDROID_SDK_ROOT=${sdk_root}"
            echo "export PATH=\${PATH}:\${ANDROID_HOME}/cmdline-tools/latest/bin:\${ANDROID_HOME}/platform-tools"
        } >> "${HOME}/.bashrc"
        log "wrote ANDROID_HOME/ANDROID_SDK_ROOT/PATH exports to ~/.bashrc"
    fi

    warn "Android SDK licenses were accepted automatically (yes | sdkmanager --licenses)."
}

run_bootstrap() {
    log "running 'make bootstrap' in ${REPO_ROOT}"
    (cd "$REPO_ROOT" && make bootstrap)
}

run_check() {
    echo "==> KeyQuest provisioning check (read-only)"
    echo "    Repo root: ${REPO_ROOT}"
    echo "    Distro:    ${DISTRO_PRETTY:-unknown}"
    echo ""
    print_table
    echo ""
    echo "==> Informational only; nothing was installed."
}

print_summary() {
    echo ""
    echo "==> Provisioning summary (${DISTRO_PRETTY:-unknown distro})"
    print_table
    echo ""
    echo "==> Next steps"
    echo "  1. If you were added to the docker group: re-login or run 'newgrp docker'."
    echo "  2. Start the API dev stack:  (cd api && docker compose up -d)  then  php start.php start"
    echo "  3. Android SDK is optional: re-run with --with-android (accepts SDK licenses)."
    echo "  4. Verify the whole workspace:  make test"
}

# --- Entry point ------------------------------------------------------------

parse_args() {
    for arg in "$@"; do
        case "$arg" in
            --check)        MODE="check" ;;
            --skip-system)  SKIP_SYSTEM=1 ;;
            --with-android) WITH_ANDROID=1 ;;
            --skip-android) WITH_ANDROID=0 ;;
            -h|--help)      usage; exit 0 ;;
            *) die "unknown flag '${arg}' (run with --help)" ;;
        esac
    done
}

main() {
    if [ ! -f "${REPO_ROOT}/Makefile" ]; then
        die "repo root not found at ${REPO_ROOT} (expected a Makefile) — run from the KeyQuest monorepo"
    fi

    detect_distro

    if [ "$MODE" = "check" ]; then
        run_check
        exit 0
    fi

    if [ "$SKIP_SYSTEM" -eq 0 ]; then
        install_system_packages
        setup_docker
        install_node
        install_composer
    else
        log "--skip-system: skipping OS-level installs"
    fi

    if [ "$WITH_ANDROID" -eq 1 ]; then
        install_android_sdk
    fi

    run_bootstrap
    print_summary
}

parse_args "$@"
main