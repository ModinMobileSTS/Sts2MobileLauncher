#!/usr/bin/env bash
# Build the generic offline bootstrap compatibility pack.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
REPO_ROOT="$(cd "$ROOT/.." && pwd -P)"
if [[ -f "$REPO_ROOT/tools/env/load-local-config.sh" ]]; then
  # shellcheck disable=SC1091
  source "$REPO_ROOT/tools/env/load-local-config.sh"
  sts2_init_env
fi

DOTNET_BIN="${DOTNET_BIN:-dotnet}"
if command -v sts2_config_path >/dev/null 2>&1; then
  DOTNET_BIN="$(sts2_config_path DOTNET_BIN build.dotnet "$DOTNET_BIN")"
fi
PROJECT="$ROOT/src/STS2OfflineBootstrap/STS2OfflineBootstrap.csproj"
OUT_ROOT="${OFFLINE_BOOTSTRAP_OUT_ROOT:-$ROOT/dist/offline-bootstrap}"
PACK_ID="${OFFLINE_BOOTSTRAP_PACK_ID:-sts2-android-offline-bootstrap}"
DISPLAY_NAME="${OFFLINE_BOOTSTRAP_DISPLAY_NAME:-STS2 Offline Bootstrap}"
COMPAT_VERSION="${OFFLINE_BOOTSTRAP_VERSION:-0.2.0-dev}"
CHANNEL="${OFFLINE_BOOTSTRAP_CHANNEL:-fallback}"
TARGET_ID="${OFFLINE_BOOTSTRAP_TARGET_ID:-offline-any}"
REFERENCE_DIR="${OFFLINE_BOOTSTRAP_REFERENCE_DIR:-}"

if command -v sts2_config_path >/dev/null 2>&1; then
  REFERENCE_DIR="$(sts2_config_path OFFLINE_BOOTSTRAP_REFERENCE_DIR offline_bootstrap.reference_dir "${REFERENCE_DIR:-$REPO_ROOT/android/assets/dotnet_bcl}")"
fi
if [[ -z "$REFERENCE_DIR" ]]; then
  REFERENCE_DIR="$REPO_ROOT/android/assets/dotnet_bcl"
fi
if [[ ! -f "$REFERENCE_DIR/GodotSharp.dll" && -n "${STS2_ANDROID_RUNTIME_REFERENCE_ROOT:-}" ]]; then
  candidate="$STS2_ANDROID_RUNTIME_REFERENCE_ROOT/assets/dotnet_bcl"
  if [[ -f "$candidate/GodotSharp.dll" ]]; then
    REFERENCE_DIR="$candidate"
  fi
fi

if [[ "$DOTNET_BIN" != "dotnet" && ! -x "$DOTNET_BIN" ]]; then
  echo "Missing dotnet: $DOTNET_BIN" >&2
  exit 1
fi
if [[ ! -f "$PROJECT" ]]; then
  echo "Missing offline bootstrap project: $PROJECT" >&2
  exit 1
fi
if [[ ! -f "$REFERENCE_DIR/GodotSharp.dll" || ! -f "$REFERENCE_DIR/0Harmony.dll" ]]; then
  echo "Missing offline bootstrap runtime references in: $REFERENCE_DIR" >&2
  exit 1
fi

msbuild_escape_property() {
  local value="$1"
  value="${value//'%'/'%25'}"
  value="${value//';'/'%3B'}"
  value="${value//','/'%2C'}"
  printf '%s' "$value"
}

GIT_BRANCH="$(git -C "$ROOT" branch --show-current 2>/dev/null || true)"
if [[ -z "$GIT_BRANCH" ]]; then
  GIT_BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
fi
GIT_COMMIT="$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
GIT_SUBJECT="$(git -C "$ROOT" log -1 --pretty=%s 2>/dev/null || echo unknown)"
GIT_DIRTY="false"
if ! git -C "$ROOT" diff --quiet --ignore-submodules -- 2>/dev/null || ! git -C "$ROOT" diff --cached --quiet --ignore-submodules -- 2>/dev/null || [[ -n "$(git -C "$ROOT" ls-files --others --exclude-standard 2>/dev/null)" ]]; then
  GIT_DIRTY="true"
fi
BUILD_TIMESTAMP_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GIT_BRANCH_MSBUILD="$(msbuild_escape_property "$GIT_BRANCH")"
GIT_SUBJECT_MSBUILD="$(msbuild_escape_property "$GIT_SUBJECT")"

rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT/$PACK_ID/variants/$TARGET_ID"

"$ROOT/tools/test-offline-contract.sh"
"$DOTNET_BIN" build "$PROJECT" -p:RuntimeReferenceDir="$REFERENCE_DIR" -p:OfflineBootstrapVersion="$COMPAT_VERSION" -p:_CompatGitBranch="$GIT_BRANCH_MSBUILD" -p:_CompatGitCommit="$GIT_COMMIT" -p:_CompatGitCommitSubject="$GIT_SUBJECT_MSBUILD" -p:_CompatGitDirty="$GIT_DIRTY" -p:_CompatBuildTimestampUtc="$BUILD_TIMESTAMP_UTC" -v:q

DLL_OUT="$ROOT/src/STS2OfflineBootstrap/bin/Debug/net9.0/STS2Mobile.dll"
if [[ ! -f "$DLL_OUT" ]]; then
  echo "Expected build output missing: $DLL_OUT" >&2
  exit 1
fi
cp -f "$DLL_OUT" "$OUT_ROOT/$PACK_ID/variants/$TARGET_ID/STS2Mobile.dll"
"$ROOT/tools/make-offline-overlay-pck.py" "$OUT_ROOT/$PACK_ID/variants/$TARGET_ID/port_compat.pck" >/dev/null

python3 - "$OUT_ROOT/$PACK_ID/compat_manifest.json" "$PACK_ID" "$DISPLAY_NAME" "$COMPAT_VERSION" "$CHANNEL" "$TARGET_ID" "$GIT_BRANCH" "$GIT_COMMIT" "$GIT_SUBJECT" "$GIT_DIRTY" "$BUILD_TIMESTAMP_UTC" <<'PY'
import json, sys
manifest_path, pack_id, display_name, compat_version, channel, target_id, branch, commit, subject, dirty, built_at = sys.argv[1:]
data = {
    "schema": 2,
    "pack_id": pack_id,
    "pack_kind": "offline-bootstrap",
    "display_name": display_name,
    "compat_version": compat_version,
    "channel": channel,
    "runtime_contract": {
        "entry_abi": 1,
        "architectures": ["arm64-v8a"],
        "probe_contract": "offline-bootstrap-v2",
    },
    "targets": [
        {
            "target_id": target_id,
            "display_name": "Generic offline fallback",
            "versions": ["*"],
            "match_mode": "offline-wildcard",
            "selection_priority": -100,
            "artifacts": {
                "dll": f"variants/{target_id}/STS2Mobile.dll",
                "overlay_pck": f"variants/{target_id}/port_compat.pck",
            },
            "notes": [
                "Fallback target selected only when no exact installed mobile compatibility pack matches the imported payload.",
                "Game API access is resolved by conservative reflection contracts and verified at runtime by offline-bootstrap-probe.json.",
                "Known ModelDb.Init API shapes include parameterless Init() and Init(Type[]? injectedModelTypes = null); unknown semantics fail closed.",
            ],
        }
    ],
    "runtime": {
        "entry_assembly": "STS2Mobile.dll",
        "entry_type": "STS2Mobile.ModEntry",
        "entry_method": "Apply",
    },
    "resources": {
        "overlay_pck": "port_compat.pck",
    },
    "build_info": {
        "branch": branch,
        "commit": commit,
        "subject": subject,
        "dirty": dirty,
        "built_utc": built_at,
        "layout": "offline-bootstrap",
    },
    "notes": [
        "Generic offline bootstrap pack. It is not a certified full mobile compatibility pack.",
        "Launcher matching gives this pack lower priority than all exact SHA/version compatibility packs.",
        "Runtime probe v2 separates patch installation from completed ModelDb initialization and records terminal failures per payload DLL SHA.",
    ],
}
with open(manifest_path, "w", encoding="utf-8") as fh:
    json.dump(data, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY

(
  cd "$OUT_ROOT/$PACK_ID"
  find compat_manifest.json variants -type f -print | sort | xargs sha256sum > SHA256SUMS
)
(
  cd "$OUT_ROOT"
  zip -qr "$PACK_ID.zip" "$PACK_ID"
)
sha256sum "$OUT_ROOT/$PACK_ID.zip"
echo "Offline bootstrap pack: $OUT_ROOT/$PACK_ID.zip"
