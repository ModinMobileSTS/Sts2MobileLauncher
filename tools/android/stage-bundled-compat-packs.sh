#!/usr/bin/env bash
# Build/install bundled mobile compatibility pack zips into Android assets.
# Each compatibility pack is built from its own port-mod branch using a temporary
# git worktree, so version-specific patch logic does not leak across branches.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
COMPAT_ROOT="$ROOT/port-mod"
CONFIG="${BUNDLED_COMPAT_PACKS_CONFIG:-$ROOT/tools/android/bundled-compat-packs.json}"
ASSET_DIR="$ROOT/android/assets/compat_packs"
WORKTREE_ROOT="$ROOT/.agent/worktrees/compat-packs"
DOTNET_BIN="${DOTNET_BIN:-$ROOT/../s2/.local/dotnet/dotnet}"

if [[ ! -d "$COMPAT_ROOT/.git" && ! -f "$COMPAT_ROOT/.git" ]]; then
  echo "Missing compat submodule checkout: $COMPAT_ROOT" >&2
  exit 1
fi
if [[ ! -f "$CONFIG" ]]; then
  echo "Missing bundled compat pack config: $CONFIG" >&2
  exit 1
fi
if [[ ! -x "$DOTNET_BIN" ]]; then
  echo "Missing dotnet: $DOTNET_BIN" >&2
  exit 1
fi

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
    if [[ ! -x "$COMPAT_ROOT/tools/build-compat-pack.sh" ]]; then
      echo "Missing compat build script in current worktree: tools/build-compat-pack.sh" >&2
      exit 1
    fi
    (
      cd "$COMPAT_ROOT"
      DOTNET_BIN="$DOTNET_BIN" ./tools/build-compat-pack.sh
    )
    cp -f "$COMPAT_ROOT"/dist/compat-pack/*.zip "$ASSET_DIR"/
    continue
  fi

  echo "Building bundled compat pack '$pack_id' from $branch"
  git -C "$COMPAT_ROOT" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rm -rf "$worktree"
  git -C "$COMPAT_ROOT" worktree add --detach "$worktree" "$branch"
  if [[ ! -x "$worktree/tools/build-compat-pack.sh" ]]; then
    echo "Missing compat build script on $branch: tools/build-compat-pack.sh" >&2
    exit 1
  fi
  (
    cd "$worktree"
    DOTNET_BIN="$DOTNET_BIN" ./tools/build-compat-pack.sh
  )
  cp -f "$worktree"/dist/compat-pack/*.zip "$ASSET_DIR"/
  git -C "$COMPAT_ROOT" worktree remove --force "$worktree" >/dev/null 2>&1 || true
done

sha256sum "$ASSET_DIR"/*.zip
