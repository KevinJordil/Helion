#!/usr/bin/env bash
# Development helper for Helion. Run without arguments to list the commands.
set -euo pipefail

readonly APP_ID="ch.kevinjordil.helion"
readonly GB_PKG="nodomain.freeyourgadget.gadgetbridge"
readonly GB_EXPORT_DIR="/storage/emulated/0/Android/data/${GB_PKG}/files"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { echo "error: $*" >&2; exit 1; }
note() { echo "==> $*"; }

# The toolchain is often not on PATH. Find it rather than hardcoding one machine's layout.
resolve_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        echo "${JAVA_HOME}"; return
    fi
    local candidate
    for candidate in "${HOME}"/.local/opt/jdk-17* /usr/lib/jvm/*17* /usr/lib/jvm/default-java; do
        [[ -x "${candidate}/bin/java" ]] && { echo "${candidate}"; return; }
    done
    command -v java >/dev/null 2>&1 && { echo ""; return; }
    die "no JDK 17 found. Install one, or set JAVA_HOME."
}

resolve_sdk() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME}/Android/Sdk" "${HOME}/Library/Android/sdk"; do
        [[ -n "${candidate}" && -d "${candidate}" ]] && { echo "${candidate}"; return; }
    done
    die "no Android SDK found. Set ANDROID_HOME."
}

setup_env() {
    local java_home
    java_home="$(resolve_java_home)"
    if [[ -n "${java_home}" ]]; then
        export JAVA_HOME="${java_home}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
    fi
    export ANDROID_HOME="$(resolve_sdk)"
    ADB="${ANDROID_HOME}/platform-tools/adb"
    SQLITE="${ANDROID_HOME}/platform-tools/sqlite3"
}

require_device() {
    "${ADB}" devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}' \
        || die "no authorised device. Plug the phone in, enable USB debugging, and accept the prompt on screen."
}

gradle() { (cd "${REPO_ROOT}" && ./gradlew "$@"); }

# Prints the export file's size and modification time, or a message if absent.
export_stat() {
    "${ADB}" shell "ls -l ${GB_EXPORT_DIR}/ 2>/dev/null | grep -i gadgetbridge" \
        || echo "(no export file found in ${GB_EXPORT_DIR})"
}

cmd_doctor() {
    note "JDK"
    java -version 2>&1 | head -1 || die "java not runnable"
    echo "    JAVA_HOME=${JAVA_HOME:-<system default>}"
    note "Android SDK"
    echo "    ANDROID_HOME=${ANDROID_HOME}"
    [[ -x "${ADB}" ]] && echo "    adb: present" || echo "    adb: MISSING"
    note "Devices"
    "${ADB}" devices | tail -n +2 | grep -v '^$' || echo "    (none connected)"
    note "Gadgetbridge on device"
    if "${ADB}" get-state >/dev/null 2>&1; then
        "${ADB}" shell pm list packages | grep -q "${GB_PKG}" \
            && echo "    installed" || echo "    NOT installed"
        note "Export file"
        export_stat
    else
        echo "    (no device; skipped)"
    fi
}

cmd_test()    { gradle :app:testDebugUnitTest; }
cmd_build()   { gradle :app:assembleDebug; }
cmd_install() { require_device; gradle :app:installDebug; note "installed ${APP_ID}"; }

cmd_logs() {
    require_device
    local pid
    pid="$("${ADB}" shell pidof "${APP_ID}" | tr -d '\r' || true)"
    [[ -n "${pid}" ]] || die "${APP_ID} is not running. Launch it first."
    "${ADB}" logcat --pid="${pid}"
}

# Broadcasts both Gadgetbridge intents and shows whether the export file actually changed.
# This is the decisive check on the action names, which are documented but unverified.
cmd_gb_check() {
    require_device
    note "Export file BEFORE"
    export_stat
    note "Broadcasting ACTIVITY_SYNC"
    "${ADB}" shell am broadcast -a "${GB_PKG}.command.ACTIVITY_SYNC" -p "${GB_PKG}"
    note "Broadcasting TRIGGER_DATABASE_EXPORT"
    "${ADB}" shell am broadcast -a "${GB_PKG}.command.TRIGGER_DATABASE_EXPORT" -p "${GB_PKG}"
    note "Waiting 15s for the export to be written"
    sleep 15
    note "Export file AFTER"
    export_stat
    cat <<'EOF'

Read the two listings above. If the modification time changed, the action names are
correct. If it did not, the export action name is wrong: try TRIGGER_EXPORT instead
of TRIGGER_DATABASE_EXPORT in GadgetbridgeCommands.kt.
Both names live in one place so this is a one-file change.
EOF
}

cmd_pull_export() {
    require_device
    local dest="${REPO_ROOT}/Gadgetbridge.db"
    "${ADB}" pull "${GB_EXPORT_DIR}/Gadgetbridge" "${dest}" \
        || die "could not pull the export. Has Gadgetbridge exported at least once?"
    note "pulled to ${dest} (git-ignored: it holds personal health data)"
}

cmd_schema() {
    local db="${1:-${REPO_ROOT}/Gadgetbridge.db}"
    [[ -f "${db}" ]] || die "no such file: ${db}"
    [[ -x "${SQLITE}" ]] || die "sqlite3 not found in the Android SDK platform-tools"
    note "Tables holding rows in ${db}"
    local table count
    while read -r table; do
        [[ -z "${table}" ]] && continue
        count="$("${SQLITE}" "${db}" "SELECT COUNT(*) FROM \"${table}\";" 2>/dev/null || echo 0)"
        [[ "${count}" -gt 0 ]] && printf '    %-45s %s\n' "${table}" "${count}"
    done < <("${SQLITE}" "${db}" ".tables" | tr -s ' ' '\n')
    note "Schema of the tables Helion reads"
    for table in HUAMI_EXTENDED_ACTIVITY_SAMPLE HUAMI_STRESS_SAMPLE HUAMI_SPO2_SAMPLE \
                 HUAMI_PAI_SAMPLE GENERIC_HRV_VALUE_SAMPLE GENERIC_TEMPERATURE_SAMPLE; do
        echo "--- ${table} ---"
        "${SQLITE}" "${db}" ".schema ${table}" 2>&1 | head -3
    done
}

usage() {
    cat <<'EOF'
Helion development helper.

  doctor         Check the toolchain, the connected device, and Gadgetbridge
  test           Run the unit tests
  build          Assemble the debug APK
  install        Build and install on the connected device
  logs           Follow the running app's logs
  gb-check       Broadcast both Gadgetbridge intents and check the export really changed
  pull-export    Copy the device's Gadgetbridge export into the repo (git-ignored)
  schema [file]  Dump the populated tables and schema of an export database

The JDK and Android SDK are located automatically; set JAVA_HOME or ANDROID_HOME to override.
EOF
}

main() {
    local command="${1:-}"
    [[ -z "${command}" ]] && { usage; exit 0; }
    shift || true
    setup_env
    case "${command}" in
        doctor)      cmd_doctor ;;
        test)        cmd_test ;;
        build)       cmd_build ;;
        install)     cmd_install ;;
        logs)        cmd_logs ;;
        gb-check)    cmd_gb_check ;;
        pull-export) cmd_pull_export ;;
        schema)      cmd_schema "$@" ;;
        -h|--help|help) usage ;;
        *)           echo "unknown command: ${command}" >&2; echo >&2; usage; exit 1 ;;
    esac
}

main "$@"
