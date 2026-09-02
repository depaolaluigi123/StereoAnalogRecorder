#!/bin/bash
# ============================================================================
#  install-android.sh — Minimal one-shot installer for Stereo Analog Recorder
# ============================================================================
#
#  This script installs Stereo Analog Recorder (prebuilt APK) and the
#  prebuilt tinymix helper on the connected Android device.
#
#  The repository is self-contained: it ships the prebuilt APK and prebuilt
#  tinymix binaries in `dependencies/`. The script NEVER reaches the network
#  and NEVER invokes Gradle / sdkmanager / the NDK.
#
#  Required layout under `dependencies/` (everything is already committed):
#    apk/app-debug.apk           ← prebuilt debug APK
#    tinymix/arm64/tinymix       ← tinymix for arm64-v8a
#    tinymix/arm/tinymix         ← tinymix for armeabi-v7a
#    tinymix/x86_64/tinymix      ← tinymix for x86_64
#    tinymix/x86/tinymix         ← tinymix for x86
#
#  What the script does (one shot, no follow-ups, no network):
#   1. Verify host prerequisites (adb, file)
#   2. Verify dependencies/apk/app-debug.apk + dependencies/tinymix/<arch>/
#   3. Verify device authorization over USB
#   4. Detect device CPU architecture
#   5. Pick the prebuilt tinymix that matches the device ABI
#   6. Install the prebuilt APK on the device
#   7. Grant runtime permissions to the app
#   8. Deploy tinymix to the device
#   9. Replace the app's bundled tinymix with the correct one (root required)
#  10. Verify installation and report a clear summary
#
#  Usage:
#     chmod +x install-android.sh
#     ./install-android.sh
#
#     Options:
#     --device <serial>   Target a specific device
#     --no-tinymix        Skip the tinymix deploy step entirely
#     -h, --help          Show this help
#
# ============================================================================

set -euo pipefail

# ---------- Configuration ----------
APP_PACKAGE="com.stereoanalogrecorder.app"
APP_DATA_DIR="/data/data/${APP_PACKAGE}"

# Project-relative paths (resolved relative to this script's location)
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
DEPS_DIR="${PROJECT_ROOT}/dependencies"
APK_PATH="${DEPS_DIR}/apk/app-debug.apk"
DEPS_BIN_DIR="${DEPS_DIR}/tinymix"

# Where to push tinymix on the device. Final install location is decided
# later (root → app private dir; unprivileged → /data/local/tmp fallback).
TINYMIX_REMOTE="/data/local/tmp/tinymix"

# Defaults
DEVICE_SERIAL=""
SKIP_TINYMIX=false

# ---------- Colors ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[ OK  ]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN ]${NC}  $*"; }
log_error() { echo -e "${RED}[FAIL ]${NC}  $*" >&2; }
log_step()  { echo -e "${BLUE}[*]${NC}  ${CYAN}$*${NC}"; }
log_cmd()   { echo -e "${CYAN}      ▸${NC} $*"; }
log_skip()  { echo -e "${YELLOW}[SKIP ]${NC}  $*"; }

die() {
    log_error "$*"
    exit 1
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Required command '$1' is not available. Install it and try again."
}

# ---------- Parse arguments ----------
print_help() {
    sed -n '3,/^# =======$/p' "$0" | grep '^#' | sed 's/^# //' | sed 's/^#//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)        shift; DEVICE_SERIAL="$1" ;;
        --no-tinymix)    SKIP_TINYMIX=true ;;
        -h|--help)
            print_help
            exit 0
            ;;
        *)
            log_warn "Unknown argument: $1 (ignoring)"
            ;;
    esac
    shift
done

echo ""
echo -e "${CYAN}${BOLD}"
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Stereo Analog Recorder — One-Shot Installer             ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo ""

# ============================================================================
# STEP 0: Host prerequisites
# ============================================================================
log_step "0. Checking host prerequisites..."

# Core tools the script always needs.
need_cmd adb
need_cmd file
# unzip is needed only if the user later extracts anything; adb itself
# already brings its own push pipeline, so we don't need it here.

# --------------------------------------------------------------------------
# Verify the bundled dependencies are present (self-contained, no network).
# --------------------------------------------------------------------------
log_step "0b. Verifying bundled dependencies..."

if [[ ! -s "$APK_PATH" ]]; then
    die "Prebuilt APK not found at $APK_PATH.
  The repository is supposed to ship app-debug.apk under dependencies/apk/.
  If you deleted it, rebuild it once with 'cd <repo-root> && ./gradlew assembleDebug'
  and re-commit the resulting app/build/outputs/apk/debug/app-debug.apk
  to dependencies/apk/."
fi
APK_SIZE=$(stat -c%s "$APK_PATH" 2>/dev/null \
    || stat -f%z "$APK_PATH" 2>/dev/null \
    || echo "?")
log_info "APK ready: $APK_PATH ($APK_SIZE bytes)"

if [[ "$SKIP_TINYMIX" != "true" ]]; then
    missing_archs=()
    for arch in arm64 arm x86_64 x86; do
        [[ -x "$DEPS_BIN_DIR/$arch/tinymix" ]] || missing_archs+=("$arch")
    done
    if [[ ${#missing_archs[@]} -gt 0 ]]; then
        die "Prebuilt tinymix binaries missing for: ${missing_archs[*]}.
  Expected under: $DEPS_BIN_DIR/<arch>/tinymix
  Re-populate dependencies/tinymix/ (the 4 ABI variants of the tinyalsa
  prebuilt are small, ~100 KB total) or re-run with --no-tinymix."
    fi
    log_info "tinymix prebuilts ready: $(ls "$DEPS_BIN_DIR" | tr '\n' ' ')"
fi

# ============================================================================
# STEP 1: Connect & authorize device
# ============================================================================
log_step "1. Connecting to device..."

adb kill-server 2>/dev/null || true
sleep 1
adb start-server 2>/dev/null || true
sleep 2

ADB="adb"
if [[ -n "$DEVICE_SERIAL" ]]; then
    ADB="adb -s $DEVICE_SERIAL"
    log_info "Targeting device serial: $DEVICE_SERIAL"
fi

# Check authorization
ADB_STATE=$($ADB get-state 2>/dev/null || true)
if [[ "$ADB_STATE" == "unauthorized" ]]; then
    die "Device is unauthorized. Unlock the phone screen and tap 'Allow USB debugging' on the device prompt."
elif [[ "$ADB_STATE" != "device" ]]; then
    die "No authorized Android device detected (state: '${ADB_STATE:-unknown}'). Ensure USB debugging is enabled."
fi
log_info "Device authorized ($ADB_STATE)"

# ============================================================================
# STEP 2: Detect device architecture
# ============================================================================
log_step "2. Detecting device properties..."

DEVICE_ABI=$($ADB shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n')
DEVICE_API=$($ADB shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')
DEVICE_MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
DEVICE_MANUFACTURER=$($ADB shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r\n')

# Some devices expose a different primary ABI; we want the one Gradle/Android
# pick for the APK. Prefer ro.product.cpu.abilist's first entry — it reflects
# what Android's package manager will use.
DEVICE_ABILIST=$($ADB shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r\n')
if [[ -n "$DEVICE_ABILIST" ]]; then
    FIRST_ABI="${DEVICE_ABILIST%%,*}"
    if [[ -n "$FIRST_ABI" && "$FIRST_ABI" != "$DEVICE_ABI" ]]; then
        log_info "ABI list: $DEVICE_ABILIST — using first ('$FIRST_ABI') as primary"
        DEVICE_ABI="$FIRST_ABI"
    fi
fi

[[ -z "$DEVICE_ABI" ]] && DEVICE_ABI="arm64-v8a"
[[ -z "$DEVICE_API" ]] && DEVICE_API=26
[[ -z "$DEVICE_MODEL" ]] && DEVICE_MODEL="unknown"

log_info "Model:        $DEVICE_MODEL"
log_info "Manufacturer: $DEVICE_MANUFACTURER"
log_info "ABI:          $DEVICE_ABI"
log_info "API level:    $DEVICE_API"

# Map device ABI → tinymix prebuilt directory.
case "$DEVICE_ABI" in
    arm64-v8a|arm64|aarch64) NDK_ARCH="arm64"  ;;
    armeabi-v7a|armeabi|arm) NDK_ARCH="arm"    ;;
    x86_64)                  NDK_ARCH="x86_64" ;;
    x86|i686|i386)           NDK_ARCH="x86"    ;;
    *)
        log_warn "Unknown ABI '$DEVICE_ABI' — falling back to arm64"
        NDK_ARCH="arm64" ;;
esac
log_info "tinymix prebuilt: $DEPS_BIN_DIR/$NDK_ARCH/tinymix"

# ============================================================================
# STEP 3: Install the prebuilt APK
# ============================================================================
log_step "3. Installing the prebuilt APK..."

# Use -r (reinstall, keep data) so we don't wipe a previous install's
# settings. -t (allow test APKs) and -d (allow downgrade) make installs
# smoother on dev devices.
log_cmd "$ADB install -r -t -d $APK_PATH"
if ! $ADB install -r -t -d "$APK_PATH" 2>&1 | tail -5; then
    die "APK install failed. Check the output above for errors."
fi

INSTALLED=$($ADB shell pm list packages 2>/dev/null | grep "package:${APP_PACKAGE}$" || true)
if [[ -z "$INSTALLED" ]]; then
    die "App package ${APP_PACKAGE} not found after install — the install may have failed silently."
fi
log_info "App package installed: ${APP_PACKAGE}"

# ============================================================================
# STEP 4: Grant runtime permissions
# ============================================================================
log_step "4. Granting runtime permissions..."

declare -a PERMS=(
    "android.permission.RECORD_AUDIO"
    "android.permission.MODIFY_AUDIO_SETTINGS"
    "android.permission.POST_NOTIFICATIONS"
    "android.permission.FOREGROUND_SERVICE"
    "android.permission.FOREGROUND_SERVICE_MICROPHONE"
)

for perm in "${PERMS[@]}"; do
    if $ADB shell pm grant "$APP_PACKAGE" "$perm" >/dev/null 2>&1; then
        log_info "Granted: $perm"
    else
        log_warn "Could not grant $perm (may require user interaction)"
    fi
done

# ============================================================================
# STEP 5: Deploy tinymix to the device
# ============================================================================
if [[ "$SKIP_TINYMIX" != "true" ]]; then
    log_step "5. Deploying tinymix to the device..."

    TINYMIX_BINARY="$DEPS_BIN_DIR/$NDK_ARCH/tinymix"
    if [[ ! -x "$TINYMIX_BINARY" ]]; then
        die "Prebuilt tinymix for $NDK_ARCH not found at $TINYMIX_BINARY"
    fi
    file_out=$(file "$TINYMIX_BINARY" 2>/dev/null || echo "unknown")
    log_info "Using prebuilt: $TINYMIX_BINARY"
    log_info "  $file_out"

    $ADB push "$TINYMIX_BINARY" "$TINYMIX_REMOTE" >/dev/null 2>&1 || \
        die "Failed to push tinymix to device."
    $ADB shell "chmod 755 ${TINYMIX_REMOTE}" >/dev/null 2>&1 || true
    log_info "tinymix deployed to ${TINYMIX_REMOTE}"

    # Verify it runs
    if $ADB shell "${TINYMIX_REMOTE} --help" >/dev/null 2>&1; then
        log_info "tinymix runs correctly on this device"
    else
        log_warn "tinymix may need root to run correctly"
    fi

    # ============================================================================
    # STEP 6: Replace app's bundled tinymix (root required)
    # ============================================================================
    log_step "6. Installing tinymix into the app's private data..."

    can_root=false
    # `adb root` returns success only on userdebug builds. On production builds
    # it fails but the device may still be rooted via Magisk — try the
    # `su -c id` probe as a second check.
    if $ADB root >/dev/null 2>&1; then
        can_root=true
        log_info "adb root succeeded"
    elif $ADB shell "su -c id -u" 2>/dev/null | tr -d '\r\n' | grep -q "^0$"; then
        can_root=true
        log_info "Magisk su available (id=0 from su -c id)"
    else
        log_info "No adb root, no working Magisk su — running unprivileged"
    fi

    if [[ "$can_root" == "true" ]]; then
        $ADB shell "mkdir -p ${APP_DATA_DIR}/files" 2>/dev/null || true

        APP_TINYMIX="${APP_DATA_DIR}/files/tinymix"
        if $ADB shell "cp ${TINYMIX_REMOTE} ${APP_TINYMIX}" >/dev/null 2>&1; then
            $ADB shell "chmod 755 ${APP_TINYMIX}" >/dev/null 2>&1 || true
            # `cp` from /data/local/tmp can carry over the tmpfs SELinux context,
            # which the untrusted_app domain may not be allowed to execute.
            # restorecon re-labels the file with the app_data_file context that
            # matches /data/data/<pkg>/files/, so the app can actually exec it.
            $ADB shell "restorecon ${APP_TINYMIX}" >/dev/null 2>&1 || true
            # Single-source deployment: remove the staging copy so the only
            # tinymix on the device is the one the app actually uses. The app
            # does not consult /data/local/tmp/tinymix anymore (the in-app
            # fallback was removed to keep the on-disk layout in sync with the
            # code path), so leaving the staging copy would only invite
            # confusion.
            $ADB shell "rm ${TINYMIX_REMOTE}" >/dev/null 2>&1 || true
            # Repoint TINYMIX_REMOTE so the downstream smoke tests (Step 7)
            # run against the copy that actually matters.
            TINYMIX_REMOTE="${APP_TINYMIX}"
            log_info "Replaced app's tinymix with ${NDK_ARCH} version at ${APP_TINYMIX}"
            log_info "Removed staging copy at /data/local/tmp/tinymix — only the app-private copy remains"
        else
            log_warn "Could not install tinymix into app data — staging copy at ${TINYMIX_REMOTE} remains, but the app cannot use it (no fallback path)"
        fi
    else
        log_info "Without root, tinymix is only at ${TINYMIX_REMOTE} — the app cannot use it from there"
        log_info "Root the device with Magisk and re-run this script so tinymix can land in the app's private dir"
    fi
else
    log_skip "tinymix deploy step skipped (--no-tinymix)"
fi

# ============================================================================
# STEP 7: Verification
# ============================================================================
log_step "7. Verifying installation..."

APP_VER=$($ADB shell dumpsys package "$APP_PACKAGE" 2>/dev/null | grep "versionName=" | head -1 | sed 's/.*versionName=//' || echo "unknown")
log_info "Installed version: ${APP_VER}"

# If tinymix is in place, run a small smoke test to confirm it sees the codec.
if [[ "$SKIP_TINYMIX" != "true" ]]; then
    SMOKE=$($ADB shell "${TINYMIX_REMOTE} controls" 2>/dev/null | head -5 || true)
    if [[ -n "$SMOKE" ]]; then
        log_info "tinymix controls smoke test OK"
        log_cmd "$(echo "$SMOKE" | tr '\n' ' ' | cut -c1-120)..."
    else
        log_warn "tinymix controls smoke test failed (root may not be granted yet)"
    fi

    # Count ADC controls discoverable by tinymix (read-only, useful diagnostic)
    ADC_CT=$($ADB shell "${TINYMIX_REMOTE} contents" 2>/dev/null | grep -ci "ADC" || true)
    if [[ "${ADC_CT:-0}" -gt 0 ]]; then
        log_info "Found $ADC_CT ADC-related controls via tinymix"
    fi
fi

# ============================================================================
# Summary
# ============================================================================
echo ""
echo -e "${GREEN}${BOLD}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}${BOLD}  Installation complete!${NC}"
echo -e "${GREEN}${BOLD}════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  Package:      ${CYAN}${APP_PACKAGE}${NC}"
echo -e "  Device:       ${CYAN}${DEVICE_MODEL} (${DEVICE_ABI}, API ${DEVICE_API})${NC}"
echo -e "  APK:          ${CYAN}$APK_PATH${NC}"
if [[ "$SKIP_TINYMIX" != "true" ]]; then
    echo -e "  Architecture: ${CYAN}${NDK_ARCH}${NC}"
    echo -e "  Root access:  ${CYAN}$([ "${can_root:-false}" = "true" ] && echo 'Yes' || echo 'No — limited ALSA')${NC}"
    echo -e "  Device path:  ${CYAN}${TINYMIX_REMOTE}${NC}"
else
    echo -e "  tinymix:      ${YELLOW}skipped (--no-tinymix)${NC}"
fi
echo -e ""
echo -e "  Next steps:"
echo -e "    1. Open Stereo Analog Recorder on the device"
echo -e "    2. Grant MICROPHONE permission when prompted"
echo -e "    3. If rooted, grant root via Magisk"
echo -e "    4. Check the ALSA status indicator in the UI"
echo -e ""
if [[ "${can_root:-false}" != "true" && "$SKIP_TINYMIX" != "true" ]]; then
    echo -e "  ${YELLOW}NOTE: Without root, the analog pre-ADC gain path is unavailable.${NC}"
    echo -e "  ${YELLOW}      The app falls back to digital DSP gain only.${NC}"
    echo -e ""
fi
