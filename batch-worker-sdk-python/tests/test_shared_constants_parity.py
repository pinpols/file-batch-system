"""Python ↔ ``docs/api/sdk-shared-constants.yaml`` strict parity test.

Lane P (Java) side asserts Java enums == yaml; this Python side asserts
``batch_worker_sdk.constants`` == yaml. Together they pin all 3 vertices
(Java, yaml, Python) of the cross-language constant triangle.

Authority order matches the yaml header: Java is the source-of-truth,
yaml is a generated mirror, Python is a consumer — any drift fails this
test loudly and forces a re-mirror.

This test deliberately uses a homegrown minimal yaml parser (top-level
``key:`` + ``- value`` lines only) to avoid pulling PyYAML into runtime
or test deps; the yaml shape is intentionally tiny.

``atomic_error_codes`` is intentionally skipped while the Java side is
still an empty placeholder (per yaml header comment); add when Lane K
populates ``AtomicErrorCode.java`` and reflects the values into yaml.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from batch_worker_sdk import constants

_REPO_ROOT = Path(__file__).resolve().parents[2]
_YAML_PATH = _REPO_ROOT / "docs" / "api" / "sdk-shared-constants.yaml"

# (yaml key, python module attribute) pairs covered by this parity test.
# Keep in sync with Java SharedConstantsParityTest's covered keys.
_COVERED: list[tuple[str, str]] = [
    ("schema_versions_supported", "SCHEMA_VERSIONS_SUPPORTED"),
    ("worker_runtime_states", "WORKER_RUNTIME_STATES"),
    ("sensitive_keywords", "SENSITIVE_KEYWORDS"),
    ("task_statuses", "TASK_STATUSES"),
]


def _load_yaml() -> dict[str, list[str]]:
    """Minimal yaml loader for top-level ``key:`` + ``- value`` shape.

    Avoid PyYAML as a test dep — the file is intentionally trivial. If
    a future field needs richer parsing, swap in PyYAML at that point.
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


@pytest.mark.parametrize(("yaml_key", "python_attr"), _COVERED)
def test_python_constants_match_yaml(yaml_key: str, python_attr: str) -> None:
    yaml_data = _load_yaml()
    assert yaml_key in yaml_data, (
        f"{yaml_key!r} missing from {_YAML_PATH}; "
        "Java side must populate yaml before Python parity can pass."
    )
    yaml_side = set(yaml_data[yaml_key])
    python_side = set(getattr(constants, python_attr))
    assert python_side == yaml_side, (
        f"Parity drift for {yaml_key!r}: "
        f"python({python_attr})={sorted(python_side)} "
        f"yaml={sorted(yaml_side)}"
    )


def test_yaml_file_is_present() -> None:
    """Sanity: Lane P must ship sdk-shared-constants.yaml."""
    assert _YAML_PATH.is_file(), (
        f"Expected Lane P shared-constants yaml at {_YAML_PATH}. "
        "Lane P did not merge or the path moved — check docs/api/."
    )


def test_yaml_covered_keys_present() -> None:
    """Sanity: every key this test asserts must exist in yaml."""
    yaml_data = _load_yaml()
    missing = [k for k, _ in _COVERED if k not in yaml_data]
    assert not missing, (
        f"sdk-shared-constants.yaml missing keys {missing}. "
        "Java SharedConstantsParityTest will also fail; fix yaml first."
    )


if __name__ == "__main__":  # pragma: no cover
    sys.exit(pytest.main([__file__, "-v"]))
