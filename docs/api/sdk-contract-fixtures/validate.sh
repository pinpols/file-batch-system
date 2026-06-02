#!/usr/bin/env bash
# Lane P (drift guard): validate every fixture JSON against fixture-schema.json.
# Used by CI sdk-contract-parity.yml and locally before pushing.
#
# Requires: python3 with `jsonschema` package (pip install jsonschema).
# Exit codes:
#   0 = all fixtures valid
#   1 = one or more fixtures violate schema
#   2 = environment problem (missing python / jsonschema)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA="$HERE/fixture-schema.json"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not on PATH" >&2
  exit 2
fi

if ! python3 -c "import jsonschema" >/dev/null 2>&1; then
  echo "ERROR: 'jsonschema' python package missing. Install with: pip install jsonschema" >&2
  exit 2
fi

python3 - "$SCHEMA" "$HERE" <<'PY'
import glob
import json
import os
import sys

import jsonschema

schema_path, fixtures_dir = sys.argv[1], sys.argv[2]
with open(schema_path, "r", encoding="utf-8") as fp:
    schema = json.load(fp)

validator = jsonschema.Draft202012Validator(schema)

failed = []
fixtures = sorted(glob.glob(os.path.join(fixtures_dir, "*.json")))
checked = 0
for path in fixtures:
    name = os.path.basename(path)
    if name == "fixture-schema.json":
        continue
    with open(path, "r", encoding="utf-8") as fp:
        try:
            doc = json.load(fp)
        except json.JSONDecodeError as exc:
            failed.append(f"{name}: invalid JSON: {exc}")
            continue
    errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.path))
    if errors:
        for err in errors:
            loc = "/".join(str(p) for p in err.absolute_path) or "<root>"
            failed.append(f"{name}: {loc}: {err.message}")
    checked += 1

if failed:
    print(f"FAIL: {len(failed)} schema violation(s) across {checked} fixture(s):", file=sys.stderr)
    for line in failed:
        print(f"  - {line}", file=sys.stderr)
    sys.exit(1)

print(f"OK: {checked} fixture(s) match fixture-schema.json")
PY
