"""Lane P drift-guard stub: Python ↔ shared-constants yaml parity.

Lane P (Java) is the source-of-truth side; Java's
``SharedConstantsParityTest`` asserts the yaml matches Java enums /
static-final lists. This file is the **Python mirror stub** — when
Lane Q implements actual Python constants (enums / Final lists) it
will remove the xfail markers and compare them to the same yaml.

Until Lane Q lands, every comparison is xfail (strict) so the day
real constants appear and accidentally match yaml, the test loudly
fails and forces the SDK author to drop the xfail.

Discovery rules mirror ``tests/contract/test_contract_runner.py``:
- Walk up from this file to repo root.
- Skip silently (zero parametrize cases) if yaml is missing — should
  never happen since Lane P ships it, but defensive.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
_YAML_PATH = _REPO_ROOT / "docs" / "api" / "sdk-shared-constants.yaml"

# Keys covered by Lane P Java parity test (must stay in sync as Java grows).
_COVERED_KEYS: list[str] = [
    "schema_versions_supported",
    "worker_runtime_states",
    "sensitive_keywords",
    "task_statuses",
]


def _load_yaml() -> dict[str, list[str]]:
    """Minimal yaml loader. Avoid adding PyYAML as a test dep — Phase 0
    keeps deps empty per ``pyproject.toml`` decision. The yaml shape
    (top-level key → list-of-strings) is small enough for a homegrown
    parser; if Lane Q needs richer parsing later it can swap in PyYAML.
    """
    if not _YAML_PATH.is_file():
        return {}
    text = _YAML_PATH.read_text(encoding="utf-8")
    out: dict[str, list[str]] = {}
    current_key: str | None = None
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" ") and line.endswith(":"):
            current_key = line[:-1].strip()
            out[current_key] = []
        elif line.lstrip().startswith("- ") and current_key is not None:
            out[current_key].append(line.lstrip()[2:].strip())
        elif not line.startswith(" ") and ":" in line and not line.endswith(":"):
            # `version: 1` style top-level scalar — ignored for parity.
            current_key = None
    return out


@pytest.mark.parametrize("key", _COVERED_KEYS)
@pytest.mark.xfail(
    strict=True,
    reason=(
        "Lane Q Phase 1 stub: Python SDK has no exported constants yet. "
        "When Lane Q adds e.g. WorkerRuntimeState enum / SENSITIVE_KEYWORDS "
        "Final list, replace the body with set-equality assertion and drop xfail."
    ),
)
def test_python_constants_match_yaml(key: str) -> None:
    yaml = _load_yaml()
    assert key in yaml, f"{key} missing from sdk-shared-constants.yaml"

    # Placeholder: Python side has no constants module to import yet.
    # Lane Q follow-up will replace this with e.g.:
    #   from batch_worker_sdk.constants import WORKER_RUNTIME_STATES
    #   assert set(WORKER_RUNTIME_STATES) == set(yaml[key])
    python_side: set[str] = set()
    yaml_side = set(yaml[key])

    assert python_side == yaml_side, (
        f"Python constants drift from yaml for '{key}'. "
        f"Python={python_side}, yaml={yaml_side}. "
        f"Sync via Lane Q parity test."
    )


def test_yaml_file_is_present() -> None:
    """Sanity: Lane P must ship sdk-shared-constants.yaml. Non-xfail."""
    assert _YAML_PATH.is_file(), (
        f"Expected Lane P shared-constants yaml at {_YAML_PATH}. "
        "Lane P did not merge or the path moved — check docs/api/."
    )


def test_yaml_covered_keys_present() -> None:
    """Sanity: every key Java parity asserts must exist in yaml."""
    yaml = _load_yaml()
    missing = [k for k in _COVERED_KEYS if k not in yaml]
    assert not missing, (
        f"sdk-shared-constants.yaml missing keys {missing}. "
        "Java SharedConstantsParityTest will fail; fix yaml first."
    )


if __name__ == "__main__":  # pragma: no cover
    sys.exit(pytest.main([__file__, "-v"]))
