#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
cd "$ROOT"

restored=0
while IFS= read -r -d '' entry; do
  metadata="${entry%%$'\t'*}"
  path="${entry#*$'\t'}"
  [[ "${metadata%% *}" == "100755" ]] || continue
  [[ -f "$path" ]] || continue
  chmod u+x "$path"
  ((restored += 1))
done < <(git ls-tree -r -z HEAD -- scripts load-tests/scripts .githooks)

printf 'Restored owner execute permission on %d Git-executable script files.\n' "$restored"
