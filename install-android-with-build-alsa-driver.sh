#!/bin/bash
# ============================================================================
#  install-android-with-build-alsa-driver.sh
#      Developer build/install script for Stereo Analog Recorder.
#
#      Builds the bits that ship inside the app:
#        - cross-compiles tinymix from dependencies/src/tinyalsa-master.zip
#          using the locally-installed Android NDK, for **every** supported
#          ABI (arm64-v8a, armeabi-v7a, x86_64, x86) — no per-ABI opt-out,
#          the resulting APK must always include all four so it works on
#          any device a user might install it on
#        - copies the freshly-built binaries into the APK assets folder
#          (app/src/main/assets/tinymix/<android-abi>/tinymix) so they get
#          packaged into the next Gradle build
#        - builds the debug APK with Gradle against the locally-installed
#          Android SDK (compileSdk = 34, targetSdk = 34)
#        - installs the resulting APK on the connected device from
#          app/build/outputs/apk/debug/app-debug.apk (the canonical Gradle
#          output) and grants runtime permissions
#
#      The app extracts the bundled tinymix from the APK assets into its
#      own private data directory on first launch, so no device-side binary
#      deploy is needed. There is no separate "minimal installer" because
#      there is nothing left to copy to the device — installation is a
#      single `adb install`.
# ============================================================================
#
#  The script NEVER reaches the network. It assumes the following pieces are
#  already present on the host:
#
#    - JDK 17+ (java / javac on PATH) — required by Gradle 8.x + AGP 8.2.x
#    - Android SDK with platforms;android-34, build-tools;34.0.0,
#      platform-tools, and an NDK (r27 family). Default location is
#      $HOME/Android/Sdk, override with --sdk-dir or ANDROID_HOME.
#    - make / unzip / file
#    - dependencies/src/tinyalsa-master.zip (committed in the repo)
#    - A connected, USB-debugging-enabled Android device
#
#  If any of these are missing, the script aborts with a clear message
#  pointing at what to install. It does NOT try to fetch anything.
#
#  Usage:
#     chmod +x install-android-with-build-alsa-driver.sh
#     ./install-android-with-build-alsa-driver.sh
#
#     Options:
#     --device <serial>        Target a specific device
#     --sdk-dir <path>         Override Android SDK location
#                              (default: $HOME/Android/Sdk, then $ANDROID_HOME,
#                              then $ANDROID_SDK_ROOT)
#     --no-tinymix             Skip the tinymix cross-compile + assets sync
#                              step (assets/ is left as-is — useful if you
#                              only want to rebuild the APK with Gradle)
#     --no-apk                 Skip the Gradle build + APK install step
#     -h, --help               Show this help
#
# ============================================================================

set -euo pipefail

# ---------- Configuration ----------
APP_PACKAGE="com.stereoanalogrecorder.app"
APP_DATA_DIR="/data/data/${APP_PACKAGE}"

# Android SDK component versions we need.
SDK_PLATFORM="platforms;android-34"
SDK_BUILD_TOOLS="build-tools;34.0.0"
SDK_PLATFORM_TOOLS="platform-tools"

# Project-relative paths
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
DEPS_DIR="${PROJECT_ROOT}/dependencies"
DEPS_SRC_ZIP="${DEPS_DIR}/src/tinyalsa-master.zip"
DEPS_SRC_DIR="${DEPS_DIR}/src/tinyalsa"   # extracted source (temporary)
ASSETS_BIN_DIR="${PROJECT_ROOT}/app/src/main/assets/tinymix"  # packaged into APK
APK_BUILD_PATH="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"

# Defaults
DEVICE_SERIAL=""
SDK_DIR_OVERRIDE=""
SKIP_TINYMIX=false
SKIP_APK=false

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
# Print only the top-of-file doc block (lines 3..46, between the opening
# `#!/bin/bash`/`# ====` banner and the closing `# ====` of the same banner).
print_help() {
    sed -n '3,46p' "$0" | sed -E 's/^# ?//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)        shift; DEVICE_SERIAL="$1" ;;
        --sdk-dir)       shift; SDK_DIR_OVERRIDE="$1" ;;
        --no-tinymix)    SKIP_TINYMIX=true ;;
        --no-apk)        SKIP_APK=true ;;
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
echo "║  Stereo Analog Recorder — Developer Build/Install        ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo ""

# ============================================================================
# STEP 0: Host prerequisites
# ============================================================================
log_step "0. Checking host prerequisites..."

# Core tools the script always needs.
need_cmd adb
need_cmd make
need_cmd unzip
need_cmd file

# java/javac — required by Gradle and the Android Gradle Plugin.
if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
    die "JDK 17+ is required (java/javac not on PATH). Install it and try again."
fi
JAVA_VER_RAW=$(java -version 2>&1 | head -1)
JAVA_VER_NUM=$(echo "$JAVA_VER_RAW" | grep -oE '"[0-9]+(\.[0-9]+){0,2}' | tr -d '"' | cut -d. -f1)
if [[ -z "$JAVA_VER_NUM" || "$JAVA_VER_NUM" -lt 17 ]]; then
    die "Detected Java: $JAVA_VER_RAW — Gradle 8.x requires JDK 17+. Install JDK 17 and try again."
fi
log_info "Java: $JAVA_VER_RAW"

# --------------------------------------------------------------------------
# Locate the Android SDK. We never download anything; if it isn't already
# installed we point the user at the manual install path.
# --------------------------------------------------------------------------
if [[ -n "$SDK_DIR_OVERRIDE" ]]; then
    ANDROID_SDK="$SDK_DIR_OVERRIDE"
elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
    ANDROID_SDK="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]]; then
    ANDROID_SDK="$ANDROID_SDK_ROOT"
elif [[ -d "$HOME/Android/Sdk" ]]; then
    ANDROID_SDK="$HOME/Android/Sdk"
elif [[ -d "/usr/lib/android-sdk" ]]; then
    ANDROID_SDK="/usr/lib/android-sdk"
else
    log_error "Android SDK not found."
    log_error "This script does NOT download the SDK. Please install it once:"
    log_error "  1. Download Android Studio or the command-line tools from"
    log_error "     https://developer.android.com/studio"
    log_error "  2. Install the platform-tools, platforms;android-34,"
    log_error "     build-tools;34.0.0, and an NDK (r27 family)"
    log_error "  3. Re-run with --sdk-dir /path/to/Sdk, or set ANDROID_HOME,"
    log_error "     or place the SDK at the default location:"
    log_error "     \$HOME/Android/Sdk"
    die "Android SDK is required to build this project."
fi

# Verify SDK is readable. The script does not write to it (Gradle does, via
# local.properties), so a read-only system SDK is fine.
if [[ ! -r "$ANDROID_SDK" ]]; then
    die "Android SDK at $ANDROID_SDK is not readable. Fix permissions or set --sdk-dir."
fi
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
log_info "Android SDK: $ANDROID_SDK"

# Make sure sdkmanager, adb, etc. are on PATH for this script.
export PATH="$ANDROID_SDK/platform-tools:$ANDROID_SDK/cmdline-tools/latest/bin:$ANDROID_SDK/cmdline-tools/bin:$PATH"

# --------------------------------------------------------------------------
# Verify the SDK components the build needs are present.
# --------------------------------------------------------------------------
log_step "0b. Verifying SDK + NDK components..."

missing_components=()
[[ -x "$ANDROID_SDK/platform-tools/adb" ]] || missing_components+=("platform-tools (adb)")
[[ -f "$ANDROID_SDK/platforms/android-34/android.jar" ]] \
    || missing_components+=("platforms;android-34 (android.jar)")
[[ -x "$ANDROID_SDK/build-tools/34.0.0/aapt2" ]] \
    || missing_components+=("build-tools;34.0.0 (aapt2)")

# NDK: pick the highest-versioned directory under $ANDROID_SDK/ndk/.
NDK_ROOT=""
for nv in "$ANDROID_SDK"/ndk/*/; do
    [[ -z "$nv" ]] && continue
    [[ -f "$nv/source.properties" ]] || continue
    NDK_ROOT="${nv%/}"
done
if [[ -z "$NDK_ROOT" ]]; then
    missing_components+=("NDK (any r27 under $ANDROID_SDK/ndk/)")
fi

if [[ ${#missing_components[@]} -gt 0 ]]; then
    log_error "The following Android SDK / NDK components are missing:"
    for c in "${missing_components[@]}"; do
        log_error "  - $c"
    done
    echo ""
    log_error "This script does NOT download anything. Install them with:"
    log_error "  sdkmanager \"platform-tools\" \"platforms;android-34\" \\"
    log_error "             \"build-tools;34.0.0\" \"ndk;27.0.12077973\""
    log_error "(the NDK version is the one in $ANDROID_SDK/ndk/ — adjust if different)"
    die "Required SDK/NDK components are missing."
fi
log_info "platform-tools:    $(basename "$(readlink -f "$ANDROID_SDK/platform-tools/adb" 2>/dev/null || echo "$ANDROID_SDK/platform-tools/adb")")"
log_info "platforms/android-34: present"
log_info "build-tools/34.0.0: present"
log_info "NDK: $NDK_ROOT"

# Verify the NDK has the clang cross-compiler for our target(s).
HOST_TAG="linux-x86_64"
[[ "$(uname -s)" == "Darwin" ]] && HOST_TAG="darwin-x86_64"
[[ "$(uname -s)" == "MINGW"* || "$(uname -s)" == "MSYS"* || "$(uname -s)" == "CYGWIN"* ]] && HOST_TAG="windows-x86_64"
LLVM_PREBUILT="${NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}"
[[ ! -d "$LLVM_PREBUILT/bin" ]] && LLVM_PREBUILT="${NDK_ROOT}/toolchains/llvm/prebuilt"
CLANG_DIR="${LLVM_PREBUILT}/bin"
[[ ! -d "$CLANG_DIR" ]] && die "LLVM toolchain not found in NDK at $NDK_ROOT (expected $CLANG_DIR)"
log_info "NDK clang dir: $CLANG_DIR"

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

# Check authorization. We don't hard-fail here: --no-apk or --no-tinymix
# could legitimately run against an unconnected host (e.g. for CI / cross-
# compile only). The device check is enforced per-step below.
DEVICE_AVAILABLE=false
ADB_STATE=$($ADB get-state 2>/dev/null || true)
if [[ "$ADB_STATE" == "device" ]]; then
    DEVICE_AVAILABLE=true
    log_info "Device authorized ($ADB_STATE)"
elif [[ "$ADB_STATE" == "unauthorized" ]]; then
    log_warn "Device is unauthorized — install/permission steps will be skipped"
else
    log_warn "No authorized device (state: '${ADB_STATE:-unknown}') — install/permission steps will be skipped"
fi

# ============================================================================
# STEP 2: Detect device properties (only when a device is connected).
#         Used for logging + summary; doesn't affect what we build — we
#         always compile tinymix for all 4 ABIs so the APK works on any
#         device.
# ============================================================================
DEVICE_ABI="unknown"
DEVICE_API=26
DEVICE_MODEL="unknown"
DEVICE_MANUFACTURER=""

if [[ "$DEVICE_AVAILABLE" == "true" ]]; then
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

    [[ -z "$DEVICE_ABI" ]] && DEVICE_ABI="unknown"
    [[ -z "$DEVICE_API" ]] && DEVICE_API=26
    [[ -z "$DEVICE_MODEL" ]] && DEVICE_MODEL="unknown"

    log_info "Model:        $DEVICE_MODEL"
    log_info "Manufacturer: $DEVICE_MANUFACTURER"
    log_info "ABI:          $DEVICE_ABI"
    log_info "API level:    $DEVICE_API"
fi

# ============================================================================
# STEP 3: Cross-compile tinymix from dependencies/src/tinyalsa-master.zip
#         for **every supported ABI**, writing the resulting binary
#         straight into the APK assets folder (one per ABI directory).
#         Runs BEFORE the Gradle build so the freshly-compiled binaries
#         are picked up when Gradle packages the APK.
# ============================================================================
if [[ "$SKIP_TINYMIX" != "true" ]]; then
    log_step "3. Building tinymix from source (all 4 ABIs)..."

    if [[ ! -s "$DEPS_SRC_ZIP" ]]; then
        die "tinyalsa source archive missing at $DEPS_SRC_ZIP.
  The script does NOT download the source. Place a tinyalsa source archive
  (master.zip from https://github.com/tinyalsa/tinyalsa) at this path
  and re-run, or pass --no-tinymix to skip the tinymix build/deploy."
    fi
    log_info "tinyalsa source: $DEPS_SRC_ZIP"

    # All four supported ABIs — the APK must always include tinymix for
    # every ABI so it works on any device a user might install it on.
    # Format: "<ndk-arch>:<target-triple>:<api-level>:<-march flags>"
    declare -A TARGETS=(
        [arm64]="aarch64-linux-android:28:-march=armv8-a"
        [arm]="armv7a-linux-androideabi:21:-march=armv7-a -mfpu=neon -mfloat-abi=softfp"
        [x86_64]="x86_64-linux-android:21:-march=x86-64"
        [x86]="i686-linux-android:21:-march=i686"
    )
    # Map NDK arch dir name (arm64/arm/x86_64/x86) → Android ABI directory
    # name (arm64-v8a/armeabi-v7a/x86_64/x86) used inside the APK assets.
    declare -A NDK_ARCH_TO_ANDROID_ABI=(
        [arm64]=arm64-v8a
        [arm]=armeabi-v7a
        [x86_64]=x86_64
        [x86]=x86
    )

    # Extract tinyalsa to a temporary working dir.
    TMP_EXTRACT="${DEPS_DIR}/src/_tmp_extract"
    rm -rf "$TMP_EXTRACT" 2>/dev/null || true
    mkdir -p "$TMP_EXTRACT"
    if ! unzip -qo "$DEPS_SRC_ZIP" -d "$TMP_EXTRACT"; then
        die "Failed to extract tinyalsa source from $DEPS_SRC_ZIP"
    fi
    EXTRACTED_DIR=$(find "$TMP_EXTRACT" -maxdepth 1 -mindepth 1 -type d | head -1)
    [[ -z "$EXTRACTED_DIR" ]] && die "Could not find extracted directory inside tinyalsa-master.zip"

    rm -rf "$DEPS_SRC_DIR" 2>/dev/null || true
    mv "$EXTRACTED_DIR" "$DEPS_SRC_DIR"
    rm -rf "$TMP_EXTRACT" 2>/dev/null || true

    # Verify the layout is the modern one (Makefile + src/ + utils/).
    HAS_MAKEFILE=false
    if [[ -f "$DEPS_SRC_DIR/Makefile" && -d "$DEPS_SRC_DIR/src" && -d "$DEPS_SRC_DIR/utils" ]]; then
        HAS_MAKEFILE=true
    fi
    if [[ "$HAS_MAKEFILE" != "true" ]]; then
        die "tinyalsa source is incomplete — expected Makefile + src/ + utils/ in $DEPS_SRC_DIR"
    fi

    LLVM_AR="${CLANG_DIR}/llvm-ar"
    LLVM_RANLIB="${CLANG_DIR}/llvm-ranlib"
    [[ ! -x "$LLVM_AR" ]]    && LLVM_AR="${CLANG_DIR}/aarch64-linux-android-ar"
    [[ ! -x "$LLVM_RANLIB" ]] && LLVM_RANLIB="${CLANG_DIR}/aarch64-linux-android-ranlib"

    BUILD_DIR="${DEPS_SRC_DIR}/build-android"
    rm -rf "$BUILD_DIR" 2>/dev/null || true
    mkdir -p "$BUILD_DIR"

    for arch in "${!TARGETS[@]}"; do
        IFS=':' read -r triple api march <<< "${TARGETS[$arch]}"
        target_clang="${CLANG_DIR}/${triple}${api}-clang"
        [[ -x "$target_clang" ]] || target_clang="${CLANG_DIR}/${triple}-clang"
        [[ -x "$target_clang" ]] || {
            log_warn "Skipping $arch: no NDK compiler for $triple"
            continue
        }
        CFLAGS="-O2 -Wall -D_GNU_SOURCE -fPIC $march"
        LDFLAGS_TINYMIX="-lm -ldl"

        log_info "Building tinymix for $arch ($triple)..."

        # Build libtinyalsa.a
        (
            cd "$DEPS_SRC_DIR/src"
            make clean 2>/dev/null || true
            make CC="$target_clang" AR="$LLVM_AR" RANLIB="$LLVM_RANLIB" \
                 CFLAGS="$CFLAGS" libtinyalsa.a \
                 2>"$BUILD_DIR/${arch}-src-err.log"
        ) || { cat "$BUILD_DIR/${arch}-src-err.log"; die "libtinyalsa build failed for $arch"; }
        cp "$DEPS_SRC_DIR/src/libtinyalsa.a" "$BUILD_DIR/libtinyalsa-$arch.a"

        # Build tinymix
        (
            cd "$DEPS_SRC_DIR/utils"
            make clean 2>/dev/null || true
            make CC="$target_clang" \
                 CFLAGS="-I \"$DEPS_SRC_DIR/include\" $CFLAGS" \
                 LDFLAGS="-L \"$DEPS_SRC_DIR/src\" $LDFLAGS_TINYMIX" \
                 tinymix 2>"$BUILD_DIR/${arch}-utils-err.log"
        ) || { cat "$BUILD_DIR/${arch}-utils-err.log"; die "tinymix build failed for $arch"; }

        # Drop the freshly built binary straight into the APK assets
        # folder for its Android ABI — that's what Gradle will package
        # into the APK on the next step.
        android_abi="${NDK_ARCH_TO_ANDROID_ABI[$arch]}"
        dst="$ASSETS_BIN_DIR/$android_abi/tinymix"
        mkdir -p "$(dirname "$dst")"
        cp "$DEPS_SRC_DIR/utils/tinymix" "$dst"
        chmod +x "$dst"
        file_out=$(file "$dst" 2>/dev/null || echo "unknown")
        log_info "  → $dst"
        log_info "    $file_out"
    done

    # Clean up the extracted source + build dir.
    rm -rf "$DEPS_SRC_DIR" 2>/dev/null || true
    rm -rf "$BUILD_DIR" 2>/dev/null || true
    log_info "Source + build artifacts cleaned up"
else
    log_skip "tinymix build skipped (--no-tinymix)"
fi

# ============================================================================
# STEP 4: Build the debug APK with Gradle. Runs AFTER the tinymix step so
#         the freshly-compiled per-ABI binaries are already sitting under
#         app/src/main/assets/tinymix/ when Gradle packages the APK.
# ============================================================================
APK_BUILT=false
if [[ "$SKIP_APK" != "true" ]]; then
    log_step "4. Building the debug APK..."

    # local.properties — point Gradle at the resolved SDK + NDK.
    LOCAL_PROPS="$PROJECT_ROOT/local.properties"
    NEEDS_LOCAL_PROPS_WRITE=false
    if [[ -f "$LOCAL_PROPS" ]]; then
        existing_sdk=$(grep -E '^[[:space:]]*sdk\.dir=' "$LOCAL_PROPS" 2>/dev/null \
                       | head -1 | sed -E 's/^[[:space:]]*sdk\.dir=//' || true)
        if [[ -z "$existing_sdk" || ! -d "$existing_sdk" \
              || "$(cd "$existing_sdk" 2>/dev/null && pwd -P || echo "$existing_sdk")" \
                 != "$(cd "$ANDROID_SDK" 2>/dev/null && pwd -P || echo "$ANDROID_SDK")" ]]; then
            NEEDS_LOCAL_PROPS_WRITE=true
        fi
    else
        NEEDS_LOCAL_PROPS_WRITE=true
    fi
    if [[ "$NEEDS_LOCAL_PROPS_WRITE" == "true" ]]; then
        log_info "Writing local.properties (sdk.dir=$ANDROID_SDK, ndk.dir=$NDK_ROOT)"
        {
            echo "sdk.dir=$ANDROID_SDK"
            echo "ndk.dir=$NDK_ROOT"
        } > "$LOCAL_PROPS"
    fi

    cd "$PROJECT_ROOT" || die "Cannot cd to $PROJECT_ROOT"
    [[ -f "$PROJECT_ROOT/gradlew" ]] || die "gradlew not found in $PROJECT_ROOT."
    [[ -x "$PROJECT_ROOT/gradlew" ]] || chmod +x "$PROJECT_ROOT/gradlew"

    # When local.properties changed, the Gradle daemon keeps a stale in-memory
    # snapshot. Stop it so the next build re-reads local.properties.
    if [[ "$NEEDS_LOCAL_PROPS_WRITE" == "true" ]]; then
        log_cmd "Stopping Gradle daemons (local.properties changed)..."
        ./gradlew --stop >/dev/null 2>&1 || true
    fi

    if ! ./gradlew assembleDebug 2>&1 | tail -25; then
        die "gradlew assembleDebug failed. Check the output above for errors."
    fi

    if [[ ! -f "$APK_BUILD_PATH" ]]; then
        die "APK not found at expected path: $APK_BUILD_PATH"
    fi
    APK_SIZE=$(stat -c%s "$APK_BUILD_PATH" 2>/dev/null \
        || stat -f%z "$APK_BUILD_PATH" 2>/dev/null \
        || echo "?")
    log_info "APK built: $APK_BUILD_PATH ($APK_SIZE bytes)"
    APK_BUILT=true
else
    log_skip "APK build skipped (--no-apk)"
fi

# ============================================================================
# STEP 5: Install the APK (only when a device is connected)
# ============================================================================
APK_INSTALLED=false
if [[ "$SKIP_APK" != "true" && "$DEVICE_AVAILABLE" == "true" ]]; then
    log_step "5. Installing the APK on the device..."

    if [[ ! -f "$APK_BUILD_PATH" ]]; then
        die "APK not found at $APK_BUILD_PATH. Build it first (drop --no-apk)."
    fi

    if ! $ADB install -r -t -d "$APK_BUILD_PATH" 2>&1 | tail -5; then
        die "APK install failed. Check the output above for errors."
    fi
    INSTALLED=$($ADB shell pm list packages 2>/dev/null | grep "package:${APP_PACKAGE}$" || true)
    if [[ -z "$INSTALLED" ]]; then
        die "App package ${APP_PACKAGE} not found after install — the install may have failed silently."
    fi
    log_info "App package installed: ${APP_PACKAGE}"
    APK_INSTALLED=true

    log_step "5b. Granting runtime permissions..."
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
elif [[ "$SKIP_APK" == "true" ]]; then
    log_skip "APK install skipped (--no-apk)"
else
    log_skip "APK install skipped (no authorized device)"
fi

# ============================================================================
# STEP 6: Verification (only when a device is connected)
# ============================================================================
if [[ "$DEVICE_AVAILABLE" == "true" ]]; then
    log_step "6. Verifying installation..."

    APP_VER=$($ADB shell dumpsys package "$APP_PACKAGE" 2>/dev/null | grep "versionName=" | head -1 | sed 's/.*versionName=//' || echo "unknown")
    log_info "Installed version: ${APP_VER}"

    if [[ "$SKIP_TINYMIX" != "true" ]]; then
        # tinymix lives at <app-data>/files/tinymix once the app has launched
        # and extracted it from the APK assets. We can poke at it via root
        # for a sanity check; without root we just verify the APK was
        # installed (the app will surface extraction errors in its UI).
        APP_TINYMIX="${APP_DATA_DIR}/files/tinymix"
        if $ADB shell "test -x ${APP_TINYMIX}" >/dev/null 2>&1; then
            SMOKE=$($ADB shell "${APP_TINYMIX} controls" 2>/dev/null | head -5 || true)
            if [[ -n "$SMOKE" ]]; then
                log_info "tinymix controls smoke test OK"
                log_cmd "$(echo "$SMOKE" | tr '\n' ' ' | cut -c1-120)..."
            else
                log_warn "tinymix controls smoke test failed (root may not be granted yet)"
            fi

            ADC_CT=$($ADB shell "${APP_TINYMIX} contents" 2>/dev/null | grep -ci "ADC" || true)
            if [[ "${ADC_CT:-0}" -gt 0 ]]; then
                log_info "Found $ADC_CT ADC-related controls via tinymix"
            fi
        else
            log_warn "tinymix not yet extracted at ${APP_TINYMIX} — open the app once so it can pull the binary out of the APK assets"
        fi
    fi
else
    log_skip "Verification skipped (no authorized device)"
fi

# ============================================================================
# Summary
# ============================================================================
echo ""
echo -e "${GREEN}${BOLD}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}${BOLD}  Build/install complete!${NC}"
echo -e "${GREEN}${BOLD}════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  Package:      ${CYAN}${APP_PACKAGE}${NC}"
if [[ "$DEVICE_AVAILABLE" == "true" ]]; then
    echo -e "  Device:       ${CYAN}${DEVICE_MODEL} (${DEVICE_ABI}, API ${DEVICE_API})${NC}"
fi
if [[ "$SKIP_APK" != "true" ]]; then
    echo -e "  APK:          ${CYAN}$APK_BUILD_PATH${NC}"
fi
if [[ "$SKIP_TINYMIX" != "true" ]]; then
    echo -e "  tinymix:      ${CYAN}all 4 ABIs built from source, embedded in APK assets${NC}"
    echo -e "  asset path:   ${CYAN}$ASSETS_BIN_DIR/<android-abi>/tinymix${NC}"
fi
echo -e ""
echo -e "  Next steps:"
echo -e "    1. Open Stereo Analog Recorder on the device"
echo -e "    2. Grant MICROPHONE permission when prompted"
echo -e "    3. On first launch the app will extract tinymix from the APK"
echo -e "       assets into its private files directory"
echo -e "    4. If rooted, grant root via Magisk"
echo -e "    5. Check the ALSA status indicator in the UI"
echo -e ""
if [[ "$DEVICE_AVAILABLE" == "true" ]]; then
    echo -e "  ${YELLOW}NOTE: Without root, the analog pre-ADC gain path is unavailable;${NC}"
    echo -e "  ${YELLOW}      the app falls back to digital DSP gain only.${NC}"
fi
