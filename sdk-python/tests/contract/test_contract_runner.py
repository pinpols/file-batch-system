"""Contract-fixture runner (Phase 0 stub).

Discovers every JSON fixture published by Lane N under
``docs/api/sdk-contract-fixtures/`` and registers one xfail'd test per
file. P1+ replaces the body of ``run_fixture`` with real assertions.

Design notes
------------
- Fixture discovery is relative to the repo root, computed by walking
  up from this file (`<repo>/sdk-python/tests/contract/...`). This
  keeps the runner working under `pip install -e .` and inside the
  GitHub Actions checkout (which always has the repo root at $GITHUB_WORKSPACE).
- If the fixtures directory is **missing** (Lane N has not merged
  yet), we emit ZERO parameters rather than collection-erroring out.
  CI prints a clear "fixtures not yet available" diagnostic and stays
  green — the SDK lane should not block on Lane N's merge order.
- Every parametrized case is wrapped in ``pytest.mark.xfail(strict=True)``
  so the first one we accidentally make pass loudly fails the suite
  and forces removal of the xfail marker.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

# <repo>/sdk-python/tests/contract/test_contract_runner.py -> <repo>
_REPO_ROOT = Path(__file__).resolve().parents[3]
_FIXTURES_DIR = _REPO_ROOT / "docs" / "api" / "sdk-contract-fixtures"


def _discover_fixtures() -> list[Path]:
    """Return every ``*.json`` fixture under the Lane N directory.

    Returns an empty list (not a failure) if the directory is missing.
    """
    if not _FIXTURES_DIR.is_dir():
        return []
    return sorted(p for p in _FIXTURES_DIR.glob("*.json") if p.is_file())


_FIXTURES = _discover_fixtures()
_FIXTURE_IDS = [p.stem for p in _FIXTURES]


@pytest.mark.contract
@pytest.mark.xfail(
    strict=True,
    reason="P0 stub: SDK runtime not implemented yet (see Roadmap P1+)",
)
@pytest.mark.parametrize("fixture_path", _FIXTURES, ids=_FIXTURE_IDS)
def test_contract_fixture(fixture_path: Path) -> None:
    """Run one contract fixture through the SDK and diff against ``expected``.

    Phase 0: we only verify the fixture is well-formed JSON, then fail
    on purpose so xfail marks it. Phase 1+ replaces this with the real
    SDK invocation.
    """
    payload = json.loads(fixture_path.read_text(encoding="utf-8"))
    assert isinstance(payload, dict), f"fixture {fixture_path.name} root must be an object"
    pytest.fail("SDK runtime not implemented (Phase 0 stub)")


def test_fixture_discovery_reports_count(capsys: pytest.CaptureFixture[str]) -> None:
    """Always-green meta-test that prints the fixture count for CI logs.

    Helps the ``contract-stub`` CI job surface "did Lane N's fixtures
    actually land?" without us needing a separate shell step.
    """
    count = len(_FIXTURES)
    if count == 0:
        print(
            f"[contract] 0 fixtures discovered at {_FIXTURES_DIR.relative_to(_REPO_ROOT)} "
            "(Lane N not yet merged — this is expected during Phase 0)"
        )
    else:
        print(f"[contract] {count} fixtures discovered: {', '.join(_FIXTURE_IDS)}")
    # This test must always pass — it is purely diagnostic.
    assert count >= 0
