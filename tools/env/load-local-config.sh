#!/usr/bin/env bash
# Shared local environment/config loader for STS2 Android build scripts.
# Source this file from bash scripts; it does not execute build steps by itself.

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "Usage: source tools/env/load-local-config.sh" >&2
  exit 2
fi

if [[ -z "${STS2_RE_ROOT:-}" ]]; then
  STS2_RE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
  export STS2_RE_ROOT
fi

STS2_ENV_FILE="${STS2_ENV_FILE:-$STS2_RE_ROOT/.env}"
STS2_LOCAL_CONFIG_FILE="${STS2_LOCAL_CONFIG_FILE:-$STS2_RE_ROOT/local.properties}"

_sts2_path_abs() {
  local input="$1"
  [[ -n "$input" ]] || return 0
  python3 - "$STS2_RE_ROOT" "$input" <<'PY'
from pathlib import Path
import os
import sys
root = Path(sys.argv[1])
value = os.path.expandvars(os.path.expanduser(sys.argv[2]))
path = Path(value)
if not path.is_absolute():
    path = root / path
print(path.resolve(strict=False))
PY
}

sts2_resolve_path() {
  _sts2_path_abs "$1"
}

_sts2_resolve_env_path_var() {
  local name="$1"
  local value="${!name:-}"
  [[ -n "$value" ]] || return 0
  local resolved
  resolved="$(_sts2_path_abs "$value")"
  printf -v "$name" '%s' "$resolved"
  export "$name"
}

sts2_load_dotenv() {
  if [[ "${STS2_DOTENV_LOADED:-0}" == "1" ]]; then
    return 0
  fi
  if [[ -f "$STS2_ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$STS2_ENV_FILE"
    set +a
  elif [[ "${STS2_REQUIRE_DOTENV:-0}" == "1" ]]; then
    echo "Missing $STS2_ENV_FILE. Copy .env.example to .env and edit local paths." >&2
    return 1
  fi

  if [[ -n "${STS2_REFERENCE_ROOT:-}" ]]; then
    _sts2_resolve_env_path_var STS2_REFERENCE_ROOT
    export JAVA_HOME="${JAVA_HOME:-$STS2_REFERENCE_ROOT/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64}"
    export ANDROID_HOME="${ANDROID_HOME:-$STS2_REFERENCE_ROOT/.godot-home/Android/Sdk}"
    export DOTNET_BIN="${DOTNET_BIN:-$STS2_REFERENCE_ROOT/.local/dotnet/dotnet}"
    export STS2_LAUNCHER_REFERENCE_ROOT="${STS2_LAUNCHER_REFERENCE_ROOT:-$STS2_REFERENCE_ROOT/.cache/StS2-Launcher_Mod_Manager}"
    export STS2_FMOD_PLUGIN_AAR="${STS2_FMOD_PLUGIN_AAR:-$STS2_REFERENCE_ROOT/addons/fmod/libs/android/fmod-release.aar}"
  fi

  if [[ -n "${STS2_LAUNCHER_REFERENCE_ROOT:-}" ]]; then
    _sts2_resolve_env_path_var STS2_LAUNCHER_REFERENCE_ROOT
    export STS2_ANDROID_RUNTIME_REFERENCE_ROOT="${STS2_ANDROID_RUNTIME_REFERENCE_ROOT:-$STS2_LAUNCHER_REFERENCE_ROOT/android}"
    export STS2_RUNTIME_REFERENCE_DIR="${STS2_RUNTIME_REFERENCE_DIR:-$STS2_LAUNCHER_REFERENCE_ROOT/upstream/godot-export/.godot/mono/publish/arm64}"
    export STS2_CRYPTO_NATIVE_JAR="${STS2_CRYPTO_NATIVE_JAR:-$STS2_LAUNCHER_REFERENCE_ROOT/vendor/godot/modules/mono/thirdparty/libSystem.Security.Cryptography.Native.Android.jar}"
  fi

  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

  local var
  for var in \
    JAVA_HOME \
    ANDROID_HOME \
    ANDROID_SDK_ROOT \
    DOTNET_BIN \
    STS2_ANDROID_RUNTIME_REFERENCE_ROOT \
    STS2_RUNTIME_REFERENCE_DIR \
    STS2_FMOD_PLUGIN_AAR \
    STS2_CRYPTO_NATIVE_JAR \
    STS2_ORIGINAL_V103_ROOT \
    STS2_ORIGINAL_V103_REFERENCE_DIR \
    STS2_ORIGINAL_V1061_ROOT \
    STS2_ORIGINAL_V1061_REFERENCE_DIR \
    STS2_ORIGINAL_V1070_ROOT \
    STS2_ORIGINAL_V1070_REFERENCE_DIR \
    STS2_ORIGINAL_V1071_ROOT \
    STS2_ORIGINAL_V1071_REFERENCE_DIR \
    STS2_ORIGINAL_V1080_ROOT \
    STS2_ORIGINAL_V1080_REFERENCE_DIR \
    STS2_PAYLOAD_ZIP; do
    _sts2_resolve_env_path_var "$var"
  done

  if [[ -n "${STS2_ORIGINAL_V103_ROOT:-}" ]]; then
    export STS2_ORIGINAL_V103_REFERENCE_DIR="${STS2_ORIGINAL_V103_REFERENCE_DIR:-$STS2_ORIGINAL_V103_ROOT/.godot/mono/temp/bin/Debug}"
    _sts2_resolve_env_path_var STS2_ORIGINAL_V103_REFERENCE_DIR
  fi
  if [[ -n "${STS2_ORIGINAL_V1061_ROOT:-}" ]]; then
    export STS2_ORIGINAL_V1061_REFERENCE_DIR="${STS2_ORIGINAL_V1061_REFERENCE_DIR:-$STS2_ORIGINAL_V1061_ROOT/.godot/mono/temp/bin/Debug}"
    _sts2_resolve_env_path_var STS2_ORIGINAL_V1061_REFERENCE_DIR
  fi
  if [[ -n "${STS2_ORIGINAL_V1070_ROOT:-}" ]]; then
    export STS2_ORIGINAL_V1070_REFERENCE_DIR="${STS2_ORIGINAL_V1070_REFERENCE_DIR:-$STS2_ORIGINAL_V1070_ROOT/.godot/mono/temp/bin/Debug}"
    _sts2_resolve_env_path_var STS2_ORIGINAL_V1070_REFERENCE_DIR
  fi
  if [[ -n "${STS2_ORIGINAL_V1071_ROOT:-}" ]]; then
    export STS2_ORIGINAL_V1071_REFERENCE_DIR="${STS2_ORIGINAL_V1071_REFERENCE_DIR:-$STS2_ORIGINAL_V1071_ROOT/.godot/mono/temp/bin/Debug}"
    _sts2_resolve_env_path_var STS2_ORIGINAL_V1071_REFERENCE_DIR
  fi
  if [[ -n "${STS2_ORIGINAL_V1080_ROOT:-}" ]]; then
    export STS2_ORIGINAL_V1080_REFERENCE_DIR="${STS2_ORIGINAL_V1080_REFERENCE_DIR:-$STS2_ORIGINAL_V1080_ROOT/.godot/mono/temp/bin/Debug}"
    _sts2_resolve_env_path_var STS2_ORIGINAL_V1080_REFERENCE_DIR
  fi

  STS2_DOTENV_LOADED=1
  export STS2_DOTENV_LOADED
}

sts2_local_property() {
  local key="$1"
  local default="${2:-}"
  python3 - "$STS2_LOCAL_CONFIG_FILE" "$key" "$default" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
key = sys.argv[2]
default = sys.argv[3]
if not path.is_file():
    print(default)
    raise SystemExit(0)
value = None
for raw in path.read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or line.startswith("!"):
        continue
    sep_index = -1
    for sep in ("=", ":"):
        idx = line.find(sep)
        if idx != -1 and (sep_index == -1 or idx < sep_index):
            sep_index = idx
    if sep_index == -1:
        k, v = line, ""
    else:
        k, v = line[:sep_index].strip(), line[sep_index + 1:].strip()
    if k == key:
        value = v
print(default if value is None else value)
PY
}

sts2_local_property_exists() {
  local key="$1"
  [[ -f "$STS2_LOCAL_CONFIG_FILE" ]] || return 1
  python3 - "$STS2_LOCAL_CONFIG_FILE" "$key" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
key = sys.argv[2]
for raw in path.read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or line.startswith("!"):
        continue
    sep_index = -1
    for sep in ("=", ":"):
        idx = line.find(sep)
        if idx != -1 and (sep_index == -1 or idx < sep_index):
            sep_index = idx
    k = line if sep_index == -1 else line[:sep_index].strip()
    if k == key:
        raise SystemExit(0)
raise SystemExit(1)
PY
}

sts2_config_value() {
  local env_name="$1"
  local property_name="$2"
  local default="${3:-}"
  if [[ -n "$env_name" ]]; then
    local env_value="${!env_name:-}"
    if [[ -n "$env_value" ]]; then
      printf '%s\n' "$env_value"
      return 0
    fi
  fi
  sts2_local_property "$property_name" "$default"
}

sts2_config_path() {
  local env_name="$1"
  local property_name="$2"
  local default="${3:-}"
  local value
  value="$(sts2_config_value "$env_name" "$property_name" "$default")"
  [[ -n "$value" ]] || return 0
  _sts2_path_abs "$value"
}

sts2_require_file() {
  local path="$1"
  local label="${2:-file}"
  if [[ ! -f "$path" ]]; then
    echo "Missing $label: $path" >&2
    return 1
  fi
}

sts2_require_dir() {
  local path="$1"
  local label="${2:-directory}"
  if [[ ! -d "$path" ]]; then
    echo "Missing $label: $path" >&2
    return 1
  fi
}

sts2_require_executable() {
  local path="$1"
  local label="${2:-executable}"
  if [[ ! -x "$path" ]]; then
    echo "Missing $label: $path" >&2
    return 1
  fi
}

sts2_require_value() {
  local value="${1:-}"
  local label="${2:-configuration value}"
  if [[ -z "$value" ]]; then
    echo "Missing $label. Copy .env.example to .env and/or local.properties.example to local.properties, then edit local paths." >&2
    return 1
  fi
}

sts2_compat_reference_dir_for_flavor() {
  local flavor="${1:-runtime}"
  if [[ -n "${COMPAT_REFERENCE_DIR:-}" ]]; then
    _sts2_path_abs "$COMPAT_REFERENCE_DIR"
    return 0
  fi
  case "$flavor" in
    original)
      printf '%s\n' "${STS2_ORIGINAL_V103_REFERENCE_DIR:-}"
      ;;
    original-v0.106.1)
      printf '%s\n' "${STS2_ORIGINAL_V1061_REFERENCE_DIR:-}"
      ;;
    original-v0.107.0)
      printf '%s\n' "${STS2_ORIGINAL_V1070_REFERENCE_DIR:-}"
      ;;
    original-v0.107.1)
      printf '%s\n' "${STS2_ORIGINAL_V1071_REFERENCE_DIR:-}"
      ;;
    original-v0.108.0)
      printf '%s\n' "${STS2_ORIGINAL_V1080_REFERENCE_DIR:-}"
      ;;
    runtime|*)
      printf '%s\n' "${STS2_RUNTIME_REFERENCE_DIR:-}"
      ;;
  esac
}

sts2_init_env() {
  sts2_load_dotenv || return 1
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:${PATH:-}"
    export LD_LIBRARY_PATH="$JAVA_HOME/lib/server${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  fi
}
