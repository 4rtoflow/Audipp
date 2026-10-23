#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
if [[ "$(uname -sm)" != "Linux x86_64" ]]; then
    echo 'Automatic setup supports Linux x86_64 only; configure JDK 17 and Android SDK manually.' >&2
    exit 1
fi
mkdir -p "$AUDIPP_ROOT/.tools/downloads"
fetch() {
    local url="$1" checksum="$2" destination="$3"
    if [[ ! -f "$destination" ]] || ! echo "$checksum  $destination" | sha256sum --check --status; then
        curl --fail --location --silent --show-error --retry 3 "$url" -o "$destination"
    fi
    echo "$checksum  $destination" | sha256sum --check --status
}
java_version="$(java -version 2>&1 | head -1 || true)"
if [[ "$java_version" != *'version "17.'* ]]; then
    archive="$AUDIPP_ROOT/.tools/downloads/jdk-17.tar.gz"
    fetch 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz' \
        '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e' "$archive"
    mkdir -p "$AUDIPP_ROOT/.tools/jdk-17"
    tar -xzf "$archive" --strip-components=1 -C "$AUDIPP_ROOT/.tools/jdk-17"
    export JAVA_HOME="$AUDIPP_ROOT/.tools/jdk-17"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/22.0/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
    if command -v sdkmanager >/dev/null 2>&1; then
        sdkmanager="$(command -v sdkmanager)"
    else
        archive="$AUDIPP_ROOT/.tools/downloads/commandlinetools-15859902.zip"
        fetch 'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' \
            '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583' "$archive"
        stage="$(mktemp -d "$AUDIPP_ROOT/.tools/cmdline-tools.XXXXXX")"
        unzip -q "$archive" -d "$stage"
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        mv "$stage/cmdline-tools" "$ANDROID_HOME/cmdline-tools/22.0"
        rmdir "$stage"
    fi
fi
if [[ "${1:-}" == '--accept-licenses' ]]; then
    # yes can receive SIGPIPE after sdkmanager finishes; preserve sdkmanager's status.
    set +o pipefail
    yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
    set -o pipefail
else
    "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
fi
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0'
printf '\nReady. Run: source scripts/linux/android-env.sh && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug\n'
