#!/usr/bin/env bash
set -euo pipefail
test_root="$(builtin cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && builtin pwd -P)"
(
    unset AUDIPP_ROOT ANDROID_HOME ANDROID_SDK_ROOT GRADLE_USER_HOME ANDROID_USER_HOME ANDROID_AVD_HOME
    shopt -s expand_aliases
    noisy_cd() { printf 'navigation message\n'; builtin cd "$@"; }
    alias cd=noisy_cd
    pwd() { printf 'custom pwd output\n'; }
    source "$test_root/scripts/linux/android-env.sh"
    [[ "$AUDIPP_ROOT" == "$test_root" ]]
    if [[ -x "$test_root/.tools/jdk-17/bin/java" ]]; then
        [[ "$JAVA_HOME" == "$test_root/.tools/jdk-17" ]]
    fi
    [[ "$GRADLE_USER_HOME" == "$test_root/.tools/gradle-user-home" ]]
)
(
    unset ANDROID_SDK_ROOT
    AUDIPP_ROOT=$'navigation message\n'"$test_root"
    export ANDROID_HOME="$AUDIPP_ROOT/.tools/android-sdk"
    export GRADLE_USER_HOME="$AUDIPP_ROOT/.tools/gradle-user-home"
    export ANDROID_USER_HOME="$AUDIPP_ROOT/.tools/android-user"
    export ANDROID_AVD_HOME="$AUDIPP_ROOT/.tools/avd"
    source "$test_root/scripts/linux/android-env.sh"
    [[ "$ANDROID_HOME" == "$test_root/.tools/android-sdk" ]]
    [[ "$GRADLE_USER_HOME" == "$test_root/.tools/gradle-user-home" ]]
    [[ "$ANDROID_USER_HOME" == "$test_root/.tools/android-user" ]]
    [[ "$ANDROID_AVD_HOME" == "$test_root/.tools/avd" ]]
    export GRADLE_USER_HOME="$test_root/.tools/custom-cache"
    source "$test_root/scripts/linux/android-env.sh"
    [[ "$GRADLE_USER_HOME" == "$test_root/.tools/custom-cache" ]]
)
printf 'Android environment: alias isolation, stale defaults recovery, custom override preservation passed.\n'
