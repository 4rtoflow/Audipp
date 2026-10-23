#!/usr/bin/env bash
# Source this file to use the repository-local tools without changing the login shell.
_audipp_previous_root="${AUDIPP_ROOT:-}"
# Interactive shells may alias cd to a command that prints navigation messages.
AUDIPP_ROOT="$(builtin cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && builtin pwd -P)"
for _audipp_env in ANDROID_HOME GRADLE_USER_HOME ANDROID_USER_HOME ANDROID_AVD_HOME; do
    # Repair only defaults produced by an earlier source with a contaminated root.
    if [[ -n "$_audipp_previous_root" && "$_audipp_previous_root" != "$AUDIPP_ROOT" &&
          "${!_audipp_env-}" == "$_audipp_previous_root/.tools/"* ]]; then
        unset "$_audipp_env"
    fi
done
unset _audipp_env _audipp_previous_root
if [[ -x "$AUDIPP_ROOT/.tools/jdk-17/bin/java" ]]; then
    export JAVA_HOME="$AUDIPP_ROOT/.tools/jdk-17"
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$AUDIPP_ROOT/.tools/android-sdk}}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$AUDIPP_ROOT/.tools/gradle-user-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$AUDIPP_ROOT/.tools/android-user}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$AUDIPP_ROOT/.tools/avd}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/cmdline-tools/22.0/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
