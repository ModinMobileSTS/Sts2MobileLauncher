#!/usr/bin/env bash
# Build/install bundled mobile compatibility pack zips into Android assets.
# Each compatibility pack is built from its own port-mod branch using a temporary
# git worktree, so version-specific patch logic does not leak across branches.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

COMPAT_ROOT="$ROOT/port-mod"
CONFIG="$(sts2_config_path BUNDLED_COMPAT_PACKS_CONFIG compat.bundled_packs_config "$ROOT/tools/android/bundled-compat-packs.json")"
ASSET_DIR="$(sts2_config_path COMPAT_PACK_ASSET_DIR compat.asset_dir "$ROOT/android/assets/compat_packs")"
WORKTREE_ROOT="$(sts2_config_path COMPAT_PACK_WORKTREE_ROOT compat.worktree_root "$ROOT/.agent/worktrees/compat-packs")"
DOTNET_BIN="$(sts2_config_path DOTNET_BIN build.dotnet "${DOTNET_BIN:-}")"
COMPAT_PACK_REMOTE="$(sts2_config_value COMPAT_PACK_REMOTE compat.pack_remote origin)"
export COMPAT_PACK_REMOTE
COMPAT_PACK_APPLY_BUILD_INFO_PATCHES="$(sts2_config_value COMPAT_PACK_APPLY_BUILD_INFO_PATCHES compat.apply_build_info_patches 1)"
export COMPAT_PACK_APPLY_BUILD_INFO_PATCHES
COMPAT_PACK_BUILD_MODE="$(sts2_config_value COMPAT_PACK_BUILD_MODE compat.pack_build_mode matrix)"
export COMPAT_PACK_BUILD_MODE

if [[ ! -d "$COMPAT_ROOT/.git" && ! -f "$COMPAT_ROOT/.git" ]]; then
  echo "Missing compat submodule checkout: $COMPAT_ROOT" >&2
  exit 1
fi
if [[ ! -f "$CONFIG" ]]; then
  echo "Missing bundled compat pack config: $CONFIG" >&2
  exit 1
fi
sts2_require_executable "$DOTNET_BIN" "dotnet"

if [[ "$COMPAT_PACK_BUILD_MODE" == "matrix" ]]; then
  mkdir -p "$ASSET_DIR"
  if [[ "${COMPAT_PACK_SKIP_ASSET_CLEAN:-0}" != "1" ]]; then
    rm -f "$ASSET_DIR"/*.zip
  fi
  if [[ ! -x "$COMPAT_ROOT/tools/build-compat-matrix.sh" ]]; then
    echo "Missing compat matrix build script: $COMPAT_ROOT/tools/build-compat-matrix.sh" >&2
    exit 1
  fi
  (
    cd "$COMPAT_ROOT"
    DOTNET_BIN="$DOTNET_BIN" ./tools/build-compat-matrix.sh
  )
  cp -f "$COMPAT_ROOT"/dist/compat-matrix/*.zip "$ASSET_DIR"/
  sha256sum "$ASSET_DIR"/*.zip
  exit 0
fi
if [[ "$COMPAT_PACK_BUILD_MODE" != "legacy" ]]; then
  echo "Unknown COMPAT_PACK_BUILD_MODE='$COMPAT_PACK_BUILD_MODE' (expected matrix or legacy)" >&2
  exit 1
fi

apply_build_info_patch() {
  local target_root="$1"
  local target_branch="${2:-}"
  if [[ "${COMPAT_PACK_APPLY_BUILD_INFO_PATCHES:-1}" == "0" ]]; then
    return 0
  fi

  local source_root="${COMPAT_PACK_BUILD_INFO_SOURCE:-$COMPAT_ROOT}"
  local rel
  for rel in \
    "STS2AndroidPortCompat/ModEntry.cs" \
    "STS2AndroidPortCompat/HarmonyAndroidCompat.cs" \
    "STS2AndroidPortCompat/HarmonyMethodReferenceImporterShim.cs" \
    "STS2AndroidPortCompat/Patches/CompatBuildInfo.cs" \
    "STS2AndroidPortCompat/Directory.Build.targets" \
    "STS2AndroidPortCompat/Android/AppPaths.cs" \
    "STS2AndroidPortCompat/Patches/EarlyLocalizationFallbackPatches.cs" \
    "STS2AndroidPortCompat/Patches/DeferredModPatchQueue.cs" \
    "STS2AndroidPortCompat/Patches/ModelDbInitPatch.cs" \
    "STS2AndroidPortCompat/Patches/ModLoaderPatches.cs" \
    "STS2AndroidPortCompat/Patches/SavePathPatches.cs" \
    "STS2AndroidPortCompat/Patches/RenderDiagnosticPatches.cs" \
    "STS2AndroidPortCompat/Patches/DebugMenuPatches.cs" \
    "STS2AndroidPortCompat/Patches/AndroidAssetCacheLifecyclePatches.cs" \
    "STS2AndroidPortCompat/Patches/LifecycleAndPerformancePatches.cs" \
    "STS2AndroidPortCompat/Patches/ShaderCompatibilityPatches.cs" \
    "STS2AndroidPortCompat/Patches/AndroidStartupLoadingScreen.cs" \
    "STS2AndroidPortCompat/Patches/AndroidSettingsMerge.cs" \
    "STS2AndroidPortCompat/Patches/AndroidInGameSettingsPatches.cs" \
    "STS2AndroidPortCompat/Patches/LanMultiplayerPatches.cs" \
    "STS2AndroidPortCompat/Patches/MobileTooltipPatches.cs" \
    "STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs" \
    "STS2AndroidPortCompat/Patches/TransitionMaterialPatches.cs" \
    "STS2AndroidPortCompat/Patches/QuickRestartPatches.cs"; do
    if [[ -f "$source_root/$rel" ]]; then
      mkdir -p "$(dirname "$target_root/$rel")"
      if [[ "$source_root/$rel" != "$target_root/$rel" ]]; then
        cp -f "$source_root/$rel" "$target_root/$rel"
      fi
    fi
  done

  if [[ -d "$source_root/overlay" ]]; then
    local source_overlay target_overlay
    source_overlay="$(cd "$source_root/overlay" && pwd -P)"
    target_overlay="$(cd "$target_root" && pwd -P)/overlay"
    if [[ "$source_overlay" != "$target_overlay" ]]; then
      rm -rf "$target_root/overlay"
      mkdir -p "$target_root/overlay"
      cp -a "$source_root/overlay/." "$target_root/overlay/"
    fi
  fi

  python3 - "$target_root" "$target_branch" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
target_branch = sys.argv[2] if len(sys.argv) > 2 else ""

# Older compat branches predate the runtime build-info log. Only inject the
# version/commit logger; do not alter startup, preload, mod-loader, or diagnostic
# Harmony patch behavior here.
mod_entry = root / "STS2AndroidPortCompat" / "ModEntry.cs"
if mod_entry.is_file():
    text = mod_entry.read_text(encoding="utf-8")
    original = text
    init_line = '        PatchHelper.Log("Initializing STS2Mobile Android port compatibility.");\n'
    if "CompatBuildInfo.Log();" not in text and init_line in text:
        text = text.replace(init_line, init_line + "        CompatBuildInfo.Log();\n", 1)
    release_info_line = '            ReleaseInfoPatches.Apply(_harmony);\n'
    if "SavePathPatches.Apply(_harmony);" not in text and release_info_line in text:
        text = text.replace(release_info_line, release_info_line + "            SavePathPatches.Apply(_harmony);\n", 1)
    shader_compat_line = '            ShaderCompatibilityPatches.Apply(_harmony);\n'
    if "TransitionMaterialPatches.Apply(_harmony);" not in text and shader_compat_line in text:
        text = text.replace(shader_compat_line, shader_compat_line + "            TransitionMaterialPatches.Apply(_harmony);\n", 1)
    map_drawing_line = '            MapDrawingSceneCachePatches.Apply(_harmony);\n'
    if "v0.107.0" not in target_branch and "1070" not in target_branch:
        text = text.replace(map_drawing_line, "")
    merchant_confirm_line = '            MerchantSelectionConfirmationPatches.Apply(_harmony);\n'
    if "MobileTooltipPatches.Apply(_harmony);" not in text and merchant_confirm_line in text:
        text = text.replace(merchant_confirm_line, merchant_confirm_line + "            MobileTooltipPatches.Apply(_harmony);\n", 1)
    if text != original:
        mod_entry.write_text(text, encoding="utf-8")

for manifest_path in root.glob("compat_manifest*.json"):
    try:
        import json
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        continue
    target = data.get("target_game")
    if isinstance(target, dict) and "source_dir" in target:
        version = str(target.get("version") or "unknown").strip() or "unknown"
        target.setdefault("source", "original_pc_reference_" + version.replace(" ", "_"))
        target.pop("source_dir", None)
        manifest_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

build_script = root / "tools" / "build-compat-pack.sh"
if build_script.is_file():
    text = build_script.read_text(encoding="utf-8")
    original = text
    marker = 'rm -rf "$OUT_ROOT"\nmkdir -p "$OUT_ROOT/$PACK_ID"\n'
    metadata = r'''GIT_BRANCH="${COMPAT_BUILD_GIT_BRANCH:-$(git -C "$ROOT" branch --show-current 2>/dev/null || true)}"
if [[ -z "$GIT_BRANCH" ]]; then
  GIT_BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
fi
GIT_COMMIT="${COMPAT_BUILD_GIT_COMMIT:-$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)}"
GIT_SUBJECT="${COMPAT_BUILD_GIT_SUBJECT:-$(git -C "$ROOT" log -1 --pretty=%s 2>/dev/null || echo unknown)}"
GIT_DIRTY="${COMPAT_BUILD_GIT_DIRTY:-false}"
if [[ "${COMPAT_BUILD_GIT_DIRTY:-}" == "" ]]; then
  if ! git -C "$ROOT" diff --quiet --ignore-submodules -- 2>/dev/null || ! git -C "$ROOT" diff --cached --quiet --ignore-submodules -- 2>/dev/null || [[ -n "$(git -C "$ROOT" ls-files --others --exclude-standard 2>/dev/null)" ]]; then
    GIT_DIRTY="true"
  fi
fi
BUILD_TIMESTAMP_UTC="${COMPAT_BUILD_TIMESTAMP_UTC:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
'''
    if marker in text:
        start = text.find(marker) + len(marker)
        end_marker = '"$DOTNET_BIN" build "$PROJECT"'
        end = text.find(end_marker, start)
        if end != -1:
            text = text[:start] + metadata + text[end:]
    if "-p:CompatReferenceDir" not in text:
        text = text.replace(
            '"$DOTNET_BIN" build "$PROJECT" -p:ReferenceFlavor="$REFERENCE_FLAVOR"',
            '"$DOTNET_BIN" build "$PROJECT" -p:ReferenceFlavor="$REFERENCE_FLAVOR" -p:CompatReferenceDir="${COMPAT_REFERENCE_DIR:-}"',
            1,
        )
    if "-p:_CompatGitBranch=\"$GIT_BRANCH\"" not in text:
        text = text.replace(
            '"$DOTNET_BIN" build "$PROJECT" -p:ReferenceFlavor="$REFERENCE_FLAVOR" -p:CompatReferenceDir="${COMPAT_REFERENCE_DIR:-}" -v:q',
            '"$DOTNET_BIN" build "$PROJECT" -p:ReferenceFlavor="$REFERENCE_FLAVOR" -p:CompatReferenceDir="${COMPAT_REFERENCE_DIR:-}" -p:_CompatGitBranch="$GIT_BRANCH" -p:_CompatGitCommit="$GIT_COMMIT" -p:_CompatGitCommitSubject="$GIT_SUBJECT" -p:_CompatGitDirty="$GIT_DIRTY" -p:_CompatBuildTimestampUtc="$BUILD_TIMESTAMP_UTC" -v:q',
            1,
        )
    if "\"build_info\"" not in text:
        copy_line = 'cp -f "$MANIFEST" "$OUT_ROOT/$PACK_ID/compat_manifest.json"\n'
        manifest_patch = r'''python3 - <<'PYMANIFEST' "$OUT_ROOT/$PACK_ID/compat_manifest.json" "$GIT_BRANCH" "$GIT_COMMIT" "$GIT_SUBJECT" "$GIT_DIRTY" "$BUILD_TIMESTAMP_UTC"
import json, sys
path, branch, commit, subject, dirty, built_at = sys.argv[1:]
with open(path, encoding="utf-8") as fh:
    data = json.load(fh)
data["build_info"] = {
    "branch": branch,
    "commit": commit,
    "subject": subject,
    "dirty": dirty,
    "built_utc": built_at,
}
with open(path, "w", encoding="utf-8") as fh:
    json.dump(data, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PYMANIFEST
'''
        if copy_line in text:
            text = text.replace(copy_line, copy_line + manifest_patch, 1)
    if text != original:
        build_script.write_text(text, encoding="utf-8")
        build_script.chmod(build_script.stat().st_mode | 0o111)
PY
  echo "Applied compat build-info patch source: $source_root"
}


_sts2_pack_reference_flavor() {
  local branch="$1"
  case "$branch" in
    *v0.108.0*|*1080*)
      printf '%s\n' "original-v0.108.0"
      ;;
    *v0.107.1*|*1071*)
      printf '%s\n' "original-v0.107.1"
      ;;
    *v0.107.0*|*1070*)
      printf '%s\n' "original-v0.107.0"
      ;;
    *v0.106.1*|*1061*)
      printf '%s\n' "original-v0.106.1"
      ;;
    *v0.103.2*|*v0.103.x*|*1032*|*103x*)
      printf '%s\n' "original"
      ;;
    *)
      sts2_config_value '' compat.default_reference_flavor original-v0.108.0
      ;;
  esac
}

resolve_compat_ref() {
  local requested="$1"
  local remote_name="${COMPAT_PACK_REMOTE:-origin}"

  if git -C "$COMPAT_ROOT" show-ref --verify --quiet "refs/heads/$requested"; then
    printf '%s\n' "$requested"
    return 0
  fi

  if git -C "$COMPAT_ROOT" rev-parse --verify --quiet "${requested}^{commit}" >/dev/null; then
    printf '%s\n' "$requested"
    return 0
  fi

  if git -C "$COMPAT_ROOT" rev-parse --verify --quiet "refs/remotes/${remote_name}/${requested}^{commit}" >/dev/null; then
    printf '%s/%s\n' "$remote_name" "$requested"
    return 0
  fi

  echo "Compat ref '$requested' not found locally; fetching ${remote_name}/${requested}..." >&2
  git -C "$COMPAT_ROOT" fetch --quiet "$remote_name" "+refs/heads/${requested}:refs/remotes/${remote_name}/${requested}" \
    || git -C "$COMPAT_ROOT" fetch --quiet "$remote_name"

  if git -C "$COMPAT_ROOT" rev-parse --verify --quiet "refs/remotes/${remote_name}/${requested}^{commit}" >/dev/null; then
    printf '%s/%s\n' "$remote_name" "$requested"
    return 0
  fi

  echo "Unable to resolve compat ref '$requested' as a local ref or ${remote_name}/$requested" >&2
  return 1
}

mkdir -p "$ASSET_DIR" "$WORKTREE_ROOT"
rm -f "$ASSET_DIR"/*.zip

python3 - <<'PY' "$CONFIG" | while IFS=$'\t' read -r pack_id branch; do
import json, sys
with open(sys.argv[1], encoding='utf-8') as fh:
    data = json.load(fh)
for pack in data.get('packs', []):
    branch = pack.get('branch')
    pack_id = pack.get('id', branch)
    if branch:
        print(f"{pack_id}\t{branch}")
PY
  safe_id="$(printf '%s' "$pack_id" | tr -c 'A-Za-z0-9._-' '_')"
  worktree="$WORKTREE_ROOT/$safe_id"
  current_branch="$(git -C "$COMPAT_ROOT" branch --show-current 2>/dev/null || true)"
  if [[ "$branch" == "$current_branch" ]]; then
    echo "Building bundled compat pack '$pack_id' from current dirty worktree ($branch)"
    pack_reference_flavor="$(_sts2_pack_reference_flavor "$branch")"
    pack_reference_dir="$(sts2_compat_reference_dir_for_flavor "$pack_reference_flavor")"
    sts2_require_value "$pack_reference_dir" "compat reference dir for $branch ($pack_reference_flavor)"
    sts2_require_file "$pack_reference_dir/sts2.dll" "sts2.dll reference for $branch ($pack_reference_flavor)"
    if [[ ! -x "$COMPAT_ROOT/tools/build-compat-pack.sh" ]]; then
      echo "Missing compat build script in current worktree: tools/build-compat-pack.sh" >&2
      exit 1
    fi
    apply_build_info_patch "$COMPAT_ROOT" "$branch"
    (
      cd "$COMPAT_ROOT"
      DOTNET_BIN="$DOTNET_BIN" \
      REFERENCE_FLAVOR="$pack_reference_flavor" \
      COMPAT_REFERENCE_DIR="$pack_reference_dir" \
      CompatReferenceDir="$pack_reference_dir" \
      COMPAT_BUILD_GIT_BRANCH="$branch" \
      ./tools/build-compat-pack.sh
    )
    cp -f "$COMPAT_ROOT"/dist/compat-pack/*.zip "$ASSET_DIR"/
    continue
  fi

  resolved_ref="$(resolve_compat_ref "$branch")"
  echo "Building bundled compat pack '$pack_id' from $resolved_ref (requested: $branch)"
  pack_reference_flavor="$(_sts2_pack_reference_flavor "$branch")"
  pack_reference_dir="$(sts2_compat_reference_dir_for_flavor "$pack_reference_flavor")"
  sts2_require_value "$pack_reference_dir" "compat reference dir for $branch ($pack_reference_flavor)"
  sts2_require_file "$pack_reference_dir/sts2.dll" "sts2.dll reference for $branch ($pack_reference_flavor)"
  git -C "$COMPAT_ROOT" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rm -rf "$worktree"
  git -C "$COMPAT_ROOT" worktree add --detach "$worktree" "$resolved_ref"
  apply_build_info_patch "$worktree" "$branch"
  if [[ ! -x "$worktree/tools/build-compat-pack.sh" ]]; then
    echo "Missing compat build script on $resolved_ref: tools/build-compat-pack.sh" >&2
    exit 1
  fi
  (
    cd "$worktree"
    DOTNET_BIN="$DOTNET_BIN" \
    REFERENCE_FLAVOR="$pack_reference_flavor" \
    COMPAT_REFERENCE_DIR="$pack_reference_dir" \
    CompatReferenceDir="$pack_reference_dir" \
    COMPAT_BUILD_GIT_BRANCH="$branch" \
    ./tools/build-compat-pack.sh
  )
  cp -f "$worktree"/dist/compat-pack/*.zip "$ASSET_DIR"/
  git -C "$COMPAT_ROOT" worktree remove --force "$worktree" >/dev/null 2>&1 || true
done

sha256sum "$ASSET_DIR"/*.zip
