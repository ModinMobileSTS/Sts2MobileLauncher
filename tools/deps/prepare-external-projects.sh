#!/usr/bin/env bash
# Prepare optional/open GitHub reference projects used by this repository.
# This never downloads commercial game payloads or prepared Godot/Mono runtime artifacts.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

CONFIG="$(sts2_config_path STS2_EXTERNAL_PROJECTS_CONFIG deps.external_projects_config "$ROOT/tools/deps/external-github-projects.json")"
DEST_ROOT="$(sts2_config_path STS2_EXTERNAL_PROJECTS_ROOT deps.external_projects_root "$ROOT/.agent/reference-repos")"
UPDATE=1
SHALLOW=0
LIST_ONLY=0
ALL=0
SKIP_SUBMODULE=0
GROUPS=()
PROJECTS=()

usage() {
  cat <<'USAGE'
Usage: tools/deps/prepare-external-projects.sh [options]

Options:
  --list                 List known external GitHub projects and exit.
  --all                  Clone all cloneable projects, including optional MOD references.
  --group <name>         Clone projects in a group; may be repeated.
  --project <id>         Clone one project by id; may be repeated.
  --root <dir>           Destination root (default: .agent/reference-repos or local config).
  --config <json>        Project list JSON (default: tools/deps/external-github-projects.json).
  --shallow              Use git clone --depth=1 for new clones.
  --no-update            Do not fetch/pull existing clones.
  --no-submodule         Do not run git submodule update --init --recursive.
  -h, --help             Show this help.

Default behavior initializes the port-mod submodule and clones projects marked
clone_by_default=true. It does not clone Gradle/JitPack-only dependencies unless
--all/--group/--project selects them and they have a git URL.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list) LIST_ONLY=1; shift ;;
    --all) ALL=1; shift ;;
    --group) GROUPS+=("${2:?Missing group name}"); shift 2 ;;
    --project) PROJECTS+=("${2:?Missing project id}"); shift 2 ;;
    --root) DEST_ROOT="$(sts2_resolve_path "${2:?Missing root dir}")"; shift 2 ;;
    --config) CONFIG="$(sts2_resolve_path "${2:?Missing config path}")"; shift 2 ;;
    --shallow) SHALLOW=1; shift ;;
    --no-update) UPDATE=0; shift ;;
    --no-submodule) SKIP_SUBMODULE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

sts2_require_file "$CONFIG" "external GitHub projects config"

if [[ "$LIST_ONLY" == "1" ]]; then
  python3 - "$CONFIG" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    data = json.load(fh)
for project in data.get("projects", []):
    groups = ",".join(project.get("groups", []))
    default = "yes" if project.get("clone_by_default") else "no"
    managed = project.get("managed_by", "git clone")
    print(f"{project.get('id')}\t{project.get('web_url') or project.get('url')}\tgroups={groups}\tdefault={default}\tmanaged={managed}\t{project.get('purpose','')}")
PY
  exit 0
fi

if [[ "$SKIP_SUBMODULE" != "1" ]]; then
  echo "==> Initializing/updating git submodules"
  git -C "$ROOT" submodule update --init --recursive
fi

mkdir -p "$DEST_ROOT"

declare -a FILTER_ARGS=("$CONFIG" "$ALL")
FILTER_ARGS+=("${#GROUPS[@]}")
FILTER_ARGS+=("${GROUPS[@]}")
FILTER_ARGS+=("${#PROJECTS[@]}")
FILTER_ARGS+=("${PROJECTS[@]}")

python3 - "${FILTER_ARGS[@]}" <<'PY' | while IFS=$'\t' read -r id name url branch managed purpose; do
import json, sys
config = sys.argv[1]
all_projects = sys.argv[2] == "1"
idx = 3
group_count = int(sys.argv[idx]); idx += 1
groups = set(sys.argv[idx:idx + group_count]); idx += group_count
project_count = int(sys.argv[idx]); idx += 1
projects = set(sys.argv[idx:idx + project_count])
with open(config, encoding="utf-8") as fh:
    data = json.load(fh)
for project in data.get("projects", []):
    pid = project.get("id", "")
    pgroups = set(project.get("groups", []))
    selected = False
    if projects:
        selected = pid in projects
    elif groups:
        selected = bool(groups & pgroups)
    elif all_projects:
        selected = True
    else:
        selected = bool(project.get("clone_by_default"))
    if not selected:
        continue
    url = project.get("url", "")
    if not url:
        continue
    branch = project.get("branch", "")
    managed = project.get("managed_by", "git clone")
    purpose = project.get("purpose", "").replace("\t", " ").replace("\n", " ")
    print("\t".join([pid, project.get("name", pid), url, branch, managed, purpose]))
PY
  if [[ -z "$id" ]]; then
    continue
  fi
  dest="$DEST_ROOT/$id"
  echo "==> $name"
  echo "    $url"
  echo "    $purpose"
  if [[ "$managed" != "git clone" && "$managed" == Gradle* ]]; then
    echo "    Managed by $managed; skipping clone."
    continue
  fi
  if [[ -d "$dest/.git" ]]; then
    echo "    Existing clone: $dest"
    if [[ "$UPDATE" == "1" ]]; then
      git -C "$dest" fetch --all --prune
      if [[ -n "$(git -C "$dest" status --porcelain)" ]]; then
        echo "    Working tree has local changes; fetched only, skip pull." >&2
      else
        current_branch="$(git -C "$dest" branch --show-current 2>/dev/null || true)"
        if [[ -n "$current_branch" ]]; then
          git -C "$dest" pull --ff-only || echo "    Pull skipped/failed; inspect $dest manually." >&2
        fi
      fi
    fi
  else
    clone_args=()
    if [[ "$SHALLOW" == "1" ]]; then
      clone_args+=(--depth=1)
    fi
    if [[ -n "$branch" ]]; then
      clone_args+=(--branch "$branch")
    fi
    git clone "${clone_args[@]}" "$url" "$dest"
  fi
done

echo "External project root: $DEST_ROOT"
echo "Note: commercial game payloads, original DLLs, keystores, and prepared Godot/Mono runtime artifacts must still be supplied via .env and are not downloaded by this script."
