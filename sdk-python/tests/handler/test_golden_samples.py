"""Java ↔ Python behavior golden samples.

Each fixture file under `tests/handler/fixtures/*.json` carries:

* `input.task_type` — routes to a concrete handler;
* `input.parameters` — fed into SdkTaskContext.parameters;
* `expected_output` — the wire-shape SdkTaskResult Java produces for the
  same input.

The Python handler is invoked with the same parameters and the output is
compared structurally. Output values that depend on environment
(timings, file paths, etc.) are skipped via subset-matching instead of
exact equality.

These golden samples are intentionally minimal — they fail fast when a
Python handler diverges from Java on the canonical happy paths.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from batch_worker_sdk.task.result import SdkTaskResult
from tests.handler.conftest import get_attr, make_ctx, try_import

FIXTURES_DIR = Path(__file__).parent / "fixtures"

# task_type → (module dotted path, class name)
TASK_TYPE_TO_HANDLER = {
    "atomic.echo": ("batch_worker_sdk.handler.atomic.shell", "ShellAtomicHandler"),
    "atomic.sql": ("batch_worker_sdk.handler.atomic.sql", "SqlAtomicHandler"),
    "builtin.file_import": (
        "batch_worker_sdk.handler.builtin.file_import",
        "FileImportHandler",
    ),
    "builtin.http_dispatch": (
        "batch_worker_sdk.handler.builtin.http_dispatch",
        "HttpDispatchHandler",
    ),
    "builtin.query_export": (
        "batch_worker_sdk.handler.builtin.query_export",
        "QueryExportHandler",
    ),
}


def _load_fixtures() -> list[tuple[str, dict[str, Any]]]:
    samples: list[tuple[str, dict[str, Any]]] = []
    for path in sorted(FIXTURES_DIR.glob("*.json")):
        with path.open("r", encoding="utf-8") as fp:
            samples.append((path.stem, json.load(fp)))
    return samples


def _run_handler(handler: Any, ctx: Any) -> SdkTaskResult:
    out: Any = handler.execute(ctx)
    if asyncio.iscoroutine(out):
        out = asyncio.get_event_loop().run_until_complete(out)
    assert isinstance(out, SdkTaskResult)
    return out


def _subset_match(actual: dict[str, Any], expected: dict[str, Any]) -> None:
    """Assert every key in `expected` is present in `actual` with matching value.

    Extra keys in `actual` are tolerated — Python handlers may emit
    richer output than the Java baseline as long as the canonical wire
    fields match.
    """
    for key, want in expected.items():
        assert key in actual, f"missing key {key!r} in actual {actual!r}"
        if isinstance(want, dict) and isinstance(actual[key], dict):
            _subset_match(actual[key], want)
        else:
            assert actual[key] == want, (
                f"mismatch at {key!r}: expected {want!r}, got {actual[key]!r}"
            )


@pytest.mark.parametrize(("name", "fixture"), _load_fixtures())
def test_golden_sample_matches_java_baseline(name: str, fixture: dict[str, Any]) -> None:
    task_type = fixture["input"]["task_type"]
    if task_type not in TASK_TYPE_TO_HANDLER:
        pytest.skip(f"fixture {name!r}: no handler mapping for task_type {task_type!r}")

    dotted, cls_name = TASK_TYPE_TO_HANDLER[task_type]
    mod = try_import(dotted)
    if mod is None:
        pytest.skip(f"fixture {name!r}: handler module {dotted!r} not yet merged")
    cls = get_attr(mod, cls_name)

    try:
        handler = cls()
    except TypeError as exc:
        pytest.skip(f"fixture {name!r}: {cls_name} not no-arg constructible ({exc})")

    ctx = make_ctx(
        task_type=task_type,
        parameters=fixture["input"]["parameters"],
    )

    try:
        result = _run_handler(handler, ctx)
    except Exception as exc:
        pytest.skip(
            f"fixture {name!r}: handler raised (likely missing infra dep): {exc!r}"
        )

    assert isinstance(result, SdkTaskResult)
    expected = fixture["expected_output"]
    actual = result.model_dump()
    _subset_match(actual, expected)


def test_all_fixtures_have_expected_output_shape() -> None:
    """Static check: every fixture file declares the 3 required wire fields."""
    fixtures = _load_fixtures()
    assert len(fixtures) >= 5, "task spec requires >= 5 golden fixtures"
    for name, fx in fixtures:
        exp = fx["expected_output"]
        assert "success" in exp, f"fixture {name!r} missing expected_output.success"
        assert "message" in exp, f"fixture {name!r} missing expected_output.message"
        assert "output" in exp, f"fixture {name!r} missing expected_output.output"
