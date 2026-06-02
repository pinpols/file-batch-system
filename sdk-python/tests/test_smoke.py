"""Smoke tests: package imports and version metadata are sane.

Phase 0 has no business logic to test — these two assertions just prove
the build pipeline (hatchling -> pip install -e -> pytest) wires up.
"""

from __future__ import annotations

import re

import batch_worker_sdk


def test_package_imports() -> None:
    """Import the top-level package without side effects."""
    assert batch_worker_sdk is not None


def test_version_is_pep440() -> None:
    """`__version__` is a PEP 440 normalized string we can publish."""
    version = batch_worker_sdk.__version__
    assert isinstance(version, str)
    # Loose PEP 440 shape: N(.N)*([abc|rc]N)?  good enough for Phase 0.
    assert re.match(r"^\d+\.\d+\.\d+([abc]|rc)?\d*$", version), version
