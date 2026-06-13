#!/usr/bin/env bash
# ADB-driven automation harness for launcher, compat-pack, MOD, preload, and performance debugging.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_load_dotenv >/dev/null 2>&1 || true

DEFAULT_PACKAGE="$(sts2_config_value STS2_ANDROID_PACKAGE android.package com.megacrit.sts2re)"
DEFAULT_APK="$(sts2_config_path STS2_DEBUG_APK android.debug.apk "$ROOT/dist/sts2-re-importer.apk")"
DEFAULT_OUT_ROOT="$(sts2_config_path STS2_DEBUG_OUT_DIR android.debug.out "$ROOT/.agent/debug/runs")"

PACKAGE="$DEFAULT_PACKAGE"
APK="$DEFAULT_APK"
OUT_ROOT="$DEFAULT_OUT_ROOT"
SERIAL="${ADB_SERIAL:-}"
RUN_ID=""
TIMEOUT_SECONDS="${STS2_DEBUG_TIMEOUT_SECONDS:-600}"
POLL_INTERVAL_SECONDS=1
TRY_ROOT=0
FORCE_STOP=0
CLEAR_LOGCAT=0
COLLECT_LOGCAT=0
LOGCAT_DURATION=0
PERFETTO_DURATION=0
PULL_AFTER=0
KEEP_INBOX=0
COMMAND=""

ADB=()
EXTRAS=()
MOD_PATHS=()

usage() {
  cat <<'EOF'
Usage:
  tools/debug/sts2-adb-debug.sh [global-options] <command> [command-options]

Global options:
  -s, --serial SERIAL       Select adb device.
  --package PACKAGE         Android application id. Default: com.megacrit.sts2re.
  --apk APK                 APK for install/build-install commands.
  --run-id ID               Stable id for this automation run.
  --out DIR                 Local output root. Default: .agent/debug/runs.
  --timeout SECONDS         Wait timeout for app-side automation. Default: 600.
  --root                    Try adb root before running.

Commands:
  build                     Build importer APK with tools/package/build_importer_apk.sh.
  install [--apk APK]       Install APK and seed automation token.
  build-install             Build, install, and seed automation token.
  token                     Seed or refresh the app-private automation token.
  status                    Query app state as JSON.
  configure [options]       Apply settings/profile/compat/MOD selections without launch.
  prepare [options]         Apply options, then run GameLaunchPreparationManager.
  launch [options]          Apply options, prepare by default, then start GodotApp.
  run [options]             Free scenario runner. Add --launch/--prepare as needed.
  open-settings [options]   Apply options, then open GameSettingsActivity.
  pull [--run-id ID]        Pull automation result and relevant app logs.
  logcat [--duration N]     Capture adb logcat to the local run directory.
  perfetto [--duration N]   Capture a Perfetto trace to the local run directory.

Scenario options:
  --mode compat|mod|perf|preload|launcher
  --payload ZIP             Push and import a PC payload zip.
  --compat ZIP              Push and import a compat-pack zip.
  --mod ZIP_OR_FILE         Push and import a MOD. Repeatable.
  --profile ID              Select launch profile.
  --payload-id ID           Select/create a profile for an existing payload id.
  --profile-name NAME       Name used when creating/updating a profile.
  --save-mode global|isolated
  --mods-mode global|isolated
  --compat-pack ID          Select installed compat pack id.
  --compat-target ID        Select schema 2 compat target id.
  --mods-enabled true|false
  --mods-only ID[,ID]       Enable only the listed MOD ids/names.
  --mods-enable ID[,ID]     Enable listed MOD ids/names.
  --mods-disable ID[,ID]    Disable listed MOD ids/names.
  --mods-only-imported      After importing MODs, enable only newly imported ids.
  --preload default|off|aggressive|tree_warmup|vfx_full_tree|animation_full|runtime_only|startup_only
                            aggressive enables protected warm cache, gameplay asset pack,
                            rendered VFX warmup, current-room safe animation warmup,
                            combat hit VFX/audio warmup, VFX cache retention,
                            learned miss list, and summary logs.
                            vfx_full_tree tries every VFX scene in the scene tree.
                            animation_full also samples all current-room Spine clips
                            and enables the full VFX tree attempt.
  --renderer opengl_es3|vulkan
  --log-level off|info|debug|very_debug
  --performance-overlay true|false
  --graphics-preset recommended|quality|compatibility|custom
  --display-preset original|mobile|custom
  --operation-preset touch|original
  --aspect-ratio VALUE
  --settings-json JSON      Merge raw keys into settings.save.
  --clear ITEMS             Comma/pipe list: texture,publish,logs,mods,compat,payloads,automation.
  --prepare / --no-prepare  Control prepare before launch.
  --launch / --no-launch    Start GodotApp for run command.
  --open-settings           Open launcher after scenario.
  --install-bundled-compat  Install bundled compat packs from APK assets.
  --force-stop              Force-stop app before starting automation.
  --clear-logcat            Clear device logcat before starting automation.
  --collect-logcat          Save adb logcat -d after the command.
  --logcat-duration N       Capture live logcat during and after the command.
  --perfetto N              Capture a Perfetto trace while running scenario.
  --pull                    Pull run result/logs after command.
  --extra KEY=VALUE         Pass any app-side extra directly.

Examples:
  tools/debug/sts2-adb-debug.sh build-install
  tools/debug/sts2-adb-debug.sh status --pull
  tools/debug/sts2-adb-debug.sh launch --mode perf --preload aggressive --clear texture,publish --logcat-duration 45 --perfetto 45 --pull
  tools/debug/sts2-adb-debug.sh run --mode mod --mod ~/Downloads/MyMod.zip --mods-only-imported --launch --pull
  tools/debug/sts2-adb-debug.sh prepare --compat dist/sts2-android-compat.zip --compat-target v0.107.0-beta --clear publish --pull
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

info() {
  echo "==> $*" >&2
}

remote_quote() {
  local value="$1"
  printf "'%s'" "${value//\'/\'\\\'\'}"
}

sanitize_name() {
  local value
  value="$(basename "$1")"
  value="${value//[^A-Za-z0-9._-]/_}"
  [[ -n "$value" && "$value" != "." && "$value" != ".." ]] || value="artifact"
  printf '%s\n' "$value"
}

now_run_id() {
  date '+%Y%m%d-%H%M%S'
}

setup_adb() {
  ADB=(adb)
  if [[ -n "$SERIAL" ]]; then
    ADB+=(-s "$SERIAL")
  fi
}

adb_shell() {
  "${ADB[@]}" shell "$@"
}

adb_exec_out() {
  "${ADB[@]}" exec-out "$@"
}

adb_exec_in() {
  "${ADB[@]}" exec-in "$@"
}

maybe_root() {
  if [[ "$TRY_ROOT" == "1" ]]; then
    info "trying adb root"
    "${ADB[@]}" root >/dev/null || true
    "${ADB[@]}" wait-for-device
  fi
}

require_device() {
  setup_adb
  maybe_root
  "${ADB[@]}" get-state >/dev/null
}

ensure_run_id() {
  if [[ -z "$RUN_ID" ]]; then
    RUN_ID="$(now_run_id)"
  fi
}

run_dir() {
  ensure_run_id
  printf '%s/%s\n' "$OUT_ROOT" "$RUN_ID"
}

ensure_out_dir() {
  local out
  out="$(run_dir)"
  mkdir -p "$out"
  printf '%s\n' "$out"
}

token_file() {
  local serial_label="${SERIAL:-default}"
  serial_label="${serial_label//[^A-Za-z0-9._-]/_}"
  local package_label="${PACKAGE//[^A-Za-z0-9._-]/_}"
  printf '%s/.agent/debug/%s-%s.token\n' "$ROOT" "$serial_label" "$package_label"
}

new_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    printf '%s-%s-%s\n' "$(date +%s)" "$$" "$RANDOM"
  fi
}

write_private_text() {
  local relative="$1"
  local text="$2"
  local dir
  dir="$(dirname "$relative")"
  local cmd="mkdir -p $(remote_quote "$dir") && cat > $(remote_quote "$relative")"
  printf '%s' "$text" | adb_exec_in run-as "$PACKAGE" sh -c "$cmd"
}

seed_token() {
  require_device
  local file token
  file="$(token_file)"
  mkdir -p "$(dirname "$file")"
  if [[ ! -s "$file" ]]; then
    new_token > "$file"
    chmod 600 "$file"
  fi
  token="$(tr -d '\r\n' < "$file")"
  [[ -n "$token" ]] || die "empty token file: $file"
  write_private_text "files/automation/token.txt" "$token"$'\n'
  info "automation token seeded for $PACKAGE"
}

read_token() {
  local file
  file="$(token_file)"
  [[ -s "$file" ]] || seed_token
  tr -d '\r\n' < "$file"
}

app_data_dir() {
  local pwd
  pwd="$(adb_shell run-as "$PACKAGE" pwd 2>/dev/null | tr -d '\r')"
  [[ -n "$pwd" ]] || die "run-as failed for $PACKAGE; install a debuggable APK first"
  printf '%s\n' "$pwd"
}

push_private_file() {
  local source="$1"
  [[ -f "$source" ]] || die "missing file: $source"
  ensure_run_id
  local app_data dest_dir name dest_rel dest_abs cmd
  app_data="$(app_data_dir)"
  dest_dir="files/automation/inbox/$RUN_ID"
  name="$(sanitize_name "$source")"
  dest_rel="$dest_dir/$name"
  dest_abs="$app_data/$dest_rel"
  cmd="mkdir -p $(remote_quote "$dest_dir") && cat > $(remote_quote "$dest_rel")"
  info "pushing $(basename "$source") -> $dest_abs"
  adb_exec_in run-as "$PACKAGE" sh -c "$cmd" < "$source"
  printf '%s\n' "$dest_abs"
}

pull_private_file() {
  local relative="$1"
  local dest="$2"
  mkdir -p "$(dirname "$dest")"
  adb_exec_out run-as "$PACKAGE" cat "$relative" > "$dest"
}

pull_private_abs_tree() {
  local abs_path="$1"
  local dest="$2"
  mkdir -p "$dest"
  local cmd="if [ -d $(remote_quote "$abs_path") ]; then tar -C $(remote_quote "$abs_path") -cf - .; fi"
  adb_exec_out run-as "$PACKAGE" sh -c "$cmd" | tar -C "$dest" -xf - 2>/dev/null || true
}

pull_private_tree() {
  local relative="$1"
  local dest="$2"
  mkdir -p "$dest"
  local cmd="if [ -d $(remote_quote "$relative") ]; then tar -C $(remote_quote "$relative") -cf - .; fi"
  adb_exec_out run-as "$PACKAGE" sh -c "$cmd" | tar -C "$dest" -xf - 2>/dev/null || true
}

append_extra() {
  local key="$1"
  local value="$2"
  EXTRAS+=(--es "$key" "$value")
}

append_bool_extra() {
  local key="$1"
  local value="$2"
  append_extra "$key" "$value"
}

parse_scenario_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode) append_extra mode "${2:?}"; shift 2 ;;
      --payload) append_extra payload_path "$(push_private_file "${2:?}")"; shift 2 ;;
      --compat) append_extra compat_path "$(push_private_file "${2:?}")"; shift 2 ;;
      --mod) MOD_PATHS+=("$(push_private_file "${2:?}")"); shift 2 ;;
      --profile) append_extra profile_id "${2:?}"; shift 2 ;;
      --payload-id) append_extra payload_id "${2:?}"; shift 2 ;;
      --profile-name) append_extra profile_name "${2:?}"; shift 2 ;;
      --save-mode) append_extra save_mode "${2:?}"; shift 2 ;;
      --mods-mode) append_extra mods_mode "${2:?}"; shift 2 ;;
      --compat-pack) append_extra compat_pack_id "${2:?}"; shift 2 ;;
      --compat-target) append_extra compat_target_id "${2:?}"; shift 2 ;;
      --mods-enabled) append_extra mods_enabled "${2:?}"; shift 2 ;;
      --mods-only) append_extra mods_only "${2:?}"; shift 2 ;;
      --mods-enable) append_extra mods_enable "${2:?}"; shift 2 ;;
      --mods-disable) append_extra mods_disable "${2:?}"; shift 2 ;;
      --mods-only-imported) append_extra mods_only_imported true; shift ;;
      --preload) append_extra preload "${2:?}"; shift 2 ;;
      --renderer) append_extra renderer "${2:?}"; shift 2 ;;
      --log-level) append_extra log_level "${2:?}"; shift 2 ;;
      --performance-overlay) append_extra performance_overlay "${2:?}"; shift 2 ;;
      --graphics-preset) append_extra graphics_preset "${2:?}"; shift 2 ;;
      --display-preset) append_extra display_preset "${2:?}"; shift 2 ;;
      --operation-preset) append_extra operation_preset "${2:?}"; shift 2 ;;
      --aspect-ratio) append_extra aspect_ratio "${2:?}"; shift 2 ;;
      --settings-json) append_extra settings_json "${2:?}"; shift 2 ;;
      --clear) append_extra clear "${2:?}"; shift 2 ;;
      --apk) APK="$(sts2_resolve_path "${2:?}")"; shift 2 ;;
      --prepare) append_extra prepare true; shift ;;
      --no-prepare) append_extra prepare false; shift ;;
      --launch) append_extra launch true; shift ;;
      --no-launch) append_extra launch false; shift ;;
      --open-settings) append_extra open_settings true; shift ;;
      --install-bundled-compat) append_extra install_bundled_compat true; shift ;;
      --force-stop) FORCE_STOP=1; shift ;;
      --clear-logcat) CLEAR_LOGCAT=1; shift ;;
      --collect-logcat) COLLECT_LOGCAT=1; shift ;;
      --logcat-duration) LOGCAT_DURATION="${2:?}"; shift 2 ;;
      --perfetto) PERFETTO_DURATION="${2:?}"; shift 2 ;;
      --duration)
        LOGCAT_DURATION="${2:?}"
        PERFETTO_DURATION="${2:?}"
        shift 2
        ;;
      --pull) PULL_AFTER=1; shift ;;
      --keep-inbox) KEEP_INBOX=1; shift ;;
      --extra)
        [[ "${2:-}" == *=* ]] || die "--extra expects KEY=VALUE"
        append_extra "${2%%=*}" "${2#*=}"
        shift 2
        ;;
      --help|-h) usage; exit 0 ;;
      *) die "unknown scenario option: $1" ;;
    esac
  done
  if [[ ${#MOD_PATHS[@]} -gt 0 ]]; then
    local joined=""
    local path
    for path in "${MOD_PATHS[@]}"; do
      if [[ -n "$joined" ]]; then
        joined+="|"
      fi
      joined+="$path"
    done
    append_extra mod_paths "$joined"
  fi
}

start_live_logcat() {
  local out="$1"
  local duration="$2"
  [[ "$duration" =~ ^[0-9]+$ && "$duration" -gt 0 ]] || return 0
  mkdir -p "$out"
  info "capturing live logcat for ${duration}s"
  "${ADB[@]}" logcat -v threadtime > "$out/logcat-live.txt" 2>&1 &
  LOGCAT_PID=$!
}

stop_live_logcat() {
  local duration="$1"
  if [[ -n "${LOGCAT_PID:-}" ]]; then
    sleep "$duration" || true
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
}

start_perfetto() {
  local out="$1"
  local duration="$2"
  [[ "$duration" =~ ^[0-9]+$ && "$duration" -gt 0 ]] || return 0
  PERFETTO_REMOTE="/data/misc/perfetto-traces/sts2-$RUN_ID.perfetto-trace"
  info "capturing perfetto for ${duration}s"
  "${ADB[@]}" shell perfetto \
    -o "$PERFETTO_REMOTE" \
    -t "${duration}s" \
    sched freq idle am wm gfx view binder_driver hal dalvik memory \
    > "$out/perfetto.log" 2>&1 &
  PERFETTO_PID=$!
}

finish_perfetto() {
  local out="$1"
  if [[ -n "${PERFETTO_PID:-}" ]]; then
    wait "$PERFETTO_PID" || true
    "${ADB[@]}" pull "$PERFETTO_REMOTE" "$out/perfetto-trace" >/dev/null 2>&1 || true
    "${ADB[@]}" shell rm -f "$PERFETTO_REMOTE" >/dev/null 2>&1 || true
  fi
}

start_automation_command() {
  local app_command="$1"
  ensure_run_id
  local token out
  token="$(read_token)"
  out="$(ensure_out_dir)"

  if [[ "$FORCE_STOP" == "1" ]]; then
    adb_shell am force-stop "$PACKAGE" >/dev/null || true
  fi
  if [[ "$CLEAR_LOGCAT" == "1" ]]; then
    "${ADB[@]}" logcat -c || true
  fi
  start_live_logcat "$out" "$LOGCAT_DURATION"
  start_perfetto "$out" "$PERFETTO_DURATION"

  info "start automation $app_command run_id=$RUN_ID"
  adb_shell am start -W \
    -n "$PACKAGE/com.godot.game.DebugAutomationActivity" \
    --es token "$token" \
    --es run_id "$RUN_ID" \
    --es command "$app_command" \
    "${EXTRAS[@]}" | tee "$out/am-start.txt"

  wait_for_result "$out"
  stop_live_logcat "$LOGCAT_DURATION"
  finish_perfetto "$out"

  if [[ "$COLLECT_LOGCAT" == "1" ]]; then
    "${ADB[@]}" logcat -d -v threadtime > "$out/logcat.txt" || true
  fi
  if [[ "$PULL_AFTER" == "1" ]]; then
    pull_artifacts "$RUN_ID" "$out"
  fi
  if [[ "$KEEP_INBOX" != "1" ]]; then
    cleanup_inbox || true
  fi
  info "result: $out/result.json"
}

wait_for_result() {
  local out="$1"
  local started now status
  started="$(date +%s)"
  while true; do
    if pull_private_file "files/automation/runs/$RUN_ID/result.json" "$out/result.json" >/dev/null 2>&1; then
      status="$(python3 - "$out/result.json" <<'PY'
import json, sys
try:
    print(json.load(open(sys.argv[1], encoding="utf-8")).get("status", ""))
except Exception:
    print("")
PY
)"
      if [[ "$status" != "running" && -n "$status" ]]; then
        cat "$out/result.json"
        echo
        [[ "$status" == "succeeded" ]] || return 1
        return 0
      fi
    fi
    now="$(date +%s)"
    if (( now - started > TIMEOUT_SECONDS )); then
      die "timed out waiting for automation result: $RUN_ID"
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
}

cleanup_inbox() {
  ensure_run_id
  local rel="files/automation/inbox/$RUN_ID"
  local cmd="rm -rf $(remote_quote "$rel")"
  adb_shell run-as "$PACKAGE" sh -c "$cmd" >/dev/null 2>&1 || true
}

pull_artifacts() {
  local run_id="$1"
  local out="${2:-$(ensure_out_dir)}"
  mkdir -p "$out/app-files" "$out/profile-logs"
  pull_private_tree "files/automation/runs/$run_id" "$out/app-files/automation-run"
  pull_private_tree "files/logs" "$out/app-files/logs"
  pull_private_tree "files/launcher" "$out/app-files/launcher"
  local logs_abs
  logs_abs="$(python3 - "$out/result.json" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    status = data.get("details", {}).get("status", {})
    print(status.get("paths", {}).get("selected_logs_root", ""))
except Exception:
    print("")
PY
)"
  if [[ -n "$logs_abs" ]]; then
    pull_private_abs_tree "$logs_abs" "$out/profile-logs"
  fi
  if [[ "$COLLECT_LOGCAT" == "1" && ! -f "$out/logcat.txt" ]]; then
    "${ADB[@]}" logcat -d -v threadtime > "$out/logcat.txt" || true
  fi
  info "pulled artifacts to $out"
}

build_apk() {
  "$ROOT/tools/package/build_importer_apk.sh"
}

install_apk() {
  require_device
  [[ -f "$APK" ]] || die "APK not found: $APK"
  info "installing $APK"
  "${ADB[@]}" install -r -d "$APK"
  seed_token
}

capture_logcat_command() {
  ensure_run_id
  local out
  out="$(ensure_out_dir)"
  local duration="${LOGCAT_DURATION:-0}"
  if [[ ! "$duration" =~ ^[0-9]+$ || "$duration" -le 0 ]]; then
    duration=30
  fi
  info "capturing logcat for ${duration}s -> $out/logcat-live.txt"
  timeout "${duration}s" "${ADB[@]}" logcat -v threadtime > "$out/logcat-live.txt" || true
}

capture_perfetto_command() {
  ensure_run_id
  local out
  out="$(ensure_out_dir)"
  local duration="$PERFETTO_DURATION"
  if [[ ! "$duration" =~ ^[0-9]+$ || "$duration" -le 0 ]]; then
    duration=30
  fi
  start_perfetto "$out" "$duration"
  finish_perfetto "$out"
  info "perfetto output: $out/perfetto-trace"
}

parse_global_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -s|--serial) SERIAL="${2:?}"; shift 2 ;;
      --package) PACKAGE="${2:?}"; shift 2 ;;
      --apk) APK="$(sts2_resolve_path "${2:?}")"; shift 2 ;;
      --run-id) RUN_ID="${2:?}"; shift 2 ;;
      --out) OUT_ROOT="$(sts2_resolve_path "${2:?}")"; shift 2 ;;
      --timeout) TIMEOUT_SECONDS="${2:?}"; shift 2 ;;
      --root) TRY_ROOT=1; shift ;;
      --help|-h) usage; exit 0 ;;
      build|install|build-install|token|status|configure|prepare|launch|run|open-settings|pull|logcat|perfetto)
        COMMAND="$1"
        shift
        REMAINING_ARGS=("$@")
        return 0
        ;;
      *) die "unknown global option or command: $1" ;;
    esac
  done
  usage
  exit 2
}

main() {
  declare -ga REMAINING_ARGS=()
  parse_global_args "$@"
  ensure_run_id

  case "$COMMAND" in
    build)
      build_apk
      ;;
    install)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      install_apk
      ;;
    build-install)
      parse_scenario_args "${REMAINING_ARGS[@]}"
      build_apk
      APK="$DEFAULT_APK"
      require_device
      install_apk
      ;;
    token)
      require_device
      seed_token
      ;;
    status)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      PULL_AFTER=1
      start_automation_command status
      ;;
    configure)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      start_automation_command configure
      ;;
    prepare)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      start_automation_command prepare
      ;;
    launch)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      append_extra launch true
      start_automation_command launch
      ;;
    run)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      start_automation_command run
      ;;
    open-settings)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      start_automation_command open_settings
      ;;
    pull)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      local out
      out="$(ensure_out_dir)"
      pull_private_file "files/automation/runs/$RUN_ID/result.json" "$out/result.json" || true
      pull_artifacts "$RUN_ID" "$out"
      ;;
    logcat)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      capture_logcat_command
      ;;
    perfetto)
      require_device
      parse_scenario_args "${REMAINING_ARGS[@]}"
      capture_perfetto_command
      ;;
    *)
      die "unknown command: $COMMAND"
      ;;
  esac
}

main "$@"
