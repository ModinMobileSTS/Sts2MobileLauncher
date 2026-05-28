#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/git/report-heads.sh [--fetch]

Print the current repository HEAD and the HEADs of each submodule's local and
remote branches.

Options:
  --fetch   Run `git fetch --all --prune` in the root repo and initialized
            submodules before reporting, so remote branch HEADs are up to date.
  -h, --help
            Show this help.
EOF
}

fetch=0
for arg in "$@"; do
  case "$arg" in
    --fetch)
      fetch=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      usage >&2
      exit 2
      ;;
  esac
done

root=$(git rev-parse --show-toplevel 2>/dev/null) || {
  echo "error: not inside a git repository" >&2
  exit 1
}

gitmodules="$root/.gitmodules"

current_branch() {
  local repo=$1
  git -C "$repo" symbolic-ref --quiet --short HEAD 2>/dev/null || echo "(detached)"
}

head_sha() {
  local repo=$1
  git -C "$repo" rev-parse --verify HEAD 2>/dev/null || echo ""
}

short_sha() {
  local sha=$1
  if [[ -n "$sha" ]]; then
    printf '%s' "${sha:0:12}"
  else
    printf '%s' "(none)"
  fi
}

dirty_summary() {
  local repo=$1
  local porcelain count
  porcelain=$(git -C "$repo" status --porcelain=v1 --untracked-files=normal 2>/dev/null || true)
  if [[ -z "$porcelain" ]]; then
    echo "clean"
  else
    count=$(printf '%s\n' "$porcelain" | sed '/^$/d' | wc -l | tr -d ' ')
    echo "dirty: ${count} change(s)"
  fi
}

upstream_summary() {
  local repo=$1
  local upstream counts ahead behind
  upstream=$(git -C "$repo" rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)
  if [[ -z "$upstream" ]]; then
    echo "no upstream"
    return
  fi

  counts=$(git -C "$repo" rev-list --left-right --count "HEAD...$upstream" 2>/dev/null || true)
  if [[ -z "$counts" ]]; then
    echo "upstream: $upstream"
    return
  fi

  read -r ahead behind <<<"$counts"
  echo "upstream: $upstream, ahead $ahead, behind $behind"
}

commit_subject() {
  local repo=$1 ref=${2:-HEAD}
  git -C "$repo" log -1 --format=%s "$ref" 2>/dev/null || echo ""
}

commit_date() {
  local repo=$1 ref=${2:-HEAD}
  git -C "$repo" log -1 --date=iso-strict --format=%cd "$ref" 2>/dev/null || echo ""
}

print_repo_head() {
  local repo=$1 title=$2
  local branch sha subject date
  branch=$(current_branch "$repo")
  sha=$(head_sha "$repo")
  subject=$(commit_subject "$repo")
  date=$(commit_date "$repo")

  echo "$title"
  echo "  path: $repo"
  echo "  branch: $branch"
  echo "  HEAD: $(short_sha "$sha")  $sha"
  [[ -n "$subject" ]] && echo "  commit: $subject"
  [[ -n "$date" ]] && echo "  date: $date"
  echo "  status: $(dirty_summary "$repo")"
  echo "  $(upstream_summary "$repo")"
}

print_refs() {
  local repo=$1 ref_prefix=$2 title=$3 current head printed=0
  current=$(current_branch "$repo")
  head=$(head_sha "$repo")

  echo "  $title:"
  while IFS=$'\x1f' read -r full_ref ref sha upstream subject; do
    [[ -z "${ref:-}" ]] && continue
    [[ "$full_ref" == */HEAD ]] && continue

    local marker=" "
    if [[ "$ref_prefix" == "refs/heads" && "$ref" == "$current" ]]; then
      marker="*"
    elif [[ -n "$head" && "$sha" == "$head" ]]; then
      marker="@"
    fi

    local upstream_part=""
    [[ -n "${upstream:-}" ]] && upstream_part=" -> $upstream"

    printf '    %s %-38s %s%s  %s\n' \
      "$marker" "$ref" "$(short_sha "$sha")" "$upstream_part" "${subject:-}"
    printed=1
  done < <(git -C "$repo" for-each-ref --sort=refname \
    --format='%(refname)%1f%(refname:short)%1f%(objectname)%1f%(upstream:short)%1f%(contents:subject)' \
    "$ref_prefix" 2>/dev/null || true)

  if [[ "$printed" -eq 0 ]]; then
    echo "    (none)"
  fi
}

submodule_entries() {
  if [[ -f "$gitmodules" ]]; then
    git config --file "$gitmodules" --get-regexp '^submodule\..*\.path$' 2>/dev/null || true
  fi
}

if [[ "$fetch" -eq 1 ]]; then
  echo "Fetching root repository..." >&2
  git -C "$root" fetch --all --prune >&2

  while read -r key path; do
    [[ -z "${key:-}" || -z "${path:-}" ]] && continue
    if git -C "$root/$path" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      echo "Fetching submodule $path..." >&2
      git -C "$root/$path" fetch --all --prune >&2 || \
        echo "warning: failed to fetch submodule $path" >&2
    fi
  done < <(submodule_entries)
fi

printf 'Git HEAD report\n'
printf 'Generated at: %s\n\n' "$(date -Iseconds)"

print_repo_head "$root" "Root repository"

if [[ ! -f "$gitmodules" ]]; then
  echo
  echo "Submodules: none (.gitmodules not found)"
  exit 0
fi

entries=$(submodule_entries)
if [[ -z "$entries" ]]; then
  echo
  echo "Submodules: none"
  exit 0
fi

echo
echo "Submodules"
while read -r key path; do
  [[ -z "${key:-}" || -z "${path:-}" ]] && continue

  name=${key#submodule.}
  name=${name%.path}
  url=$(git config --file "$gitmodules" --get "submodule.$name.url" 2>/dev/null || true)
  configured_branch=$(git config --file "$gitmodules" --get "submodule.$name.branch" 2>/dev/null || true)
  status_line=$(git -C "$root" submodule status -- "$path" 2>/dev/null || true)
  status_prefix=${status_line:0:1}
  [[ -z "$status_prefix" ]] && status_prefix="?"
  recorded_head=$(git -C "$root" ls-tree HEAD -- "$path" 2>/dev/null | awk '{print $3}')
  index_head=$(git -C "$root" ls-files -s -- "$path" 2>/dev/null | awk '{print $2}')

  echo
  echo "- $name"
  echo "  path: $path"
  [[ -n "$url" ]] && echo "  url: $url"
  [[ -n "$configured_branch" ]] && echo "  configured branch: $configured_branch"
  echo "  parent HEAD records: $(short_sha "$recorded_head")  $recorded_head"
  if [[ -n "$index_head" && "$index_head" != "$recorded_head" ]]; then
    echo "  parent index records: $(short_sha "$index_head")  $index_head"
  fi

  case "$status_prefix" in
    ' ')
      echo "  submodule status: checkout matches parent index"
      ;;
    '-')
      echo "  submodule status: not initialized"
      ;;
    '+')
      echo "  submodule status: checkout differs from parent index"
      ;;
    'U')
      echo "  submodule status: merge conflict"
      ;;
    *)
      echo "  submodule status: unknown ($status_prefix)"
      ;;
  esac

  subrepo="$root/$path"
  if ! git -C "$subrepo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "  working tree: unavailable"
    continue
  fi

  branch=$(current_branch "$subrepo")
  sha=$(head_sha "$subrepo")
  subject=$(commit_subject "$subrepo")
  date=$(commit_date "$subrepo")
  echo "  current branch: $branch"
  echo "  current HEAD: $(short_sha "$sha")  $sha"
  [[ -n "$subject" ]] && echo "  current commit: $subject"
  [[ -n "$date" ]] && echo "  current date: $date"
  echo "  working tree: $(dirty_summary "$subrepo")"
  echo "  $(upstream_summary "$subrepo")"

  print_refs "$subrepo" refs/heads "local branch HEADs"
  print_refs "$subrepo" refs/remotes "remote branch HEADs"
done <<<"$entries"
