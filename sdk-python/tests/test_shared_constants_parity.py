"""drift-guard 桩:Python ↔ shared-constants yaml 一致性。

Java 侧 ``SharedConstantsParityTest`` 是 source-of-truth,断言
yaml 与 Java enum / static-final 列表一致。本文件是 **Python 镜像桩**
—— 等 Python SDK 真正落地常量(enum / Final list)后会去掉 xfail,
并把常量与同一份 yaml 做对比。

在 Python 常量到位之前,所有对比都是 xfail(strict),这样真常量
某天意外与 yaml 对齐时,测试会大声失败,逼 SDK 作者去掉 xfail。

发现规则与 ``tests/contract/test_contract_runner.py`` 一致:
- 从本文件向上走到 repo root。
- yaml 缺失时静默跳过(0 个 parametrize 用例)—— Java 侧已经发布,
  正常不应缺,这里只是防御。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
_YAML_PATH = _REPO_ROOT / "docs" / "api" / "sdk-shared-constants.yaml"

# Java parity 测试覆盖的 key(Java 增长时必须同步)。
_COVERED_KEYS: list[str] = [
    "schema_versions_supported",
    "worker_runtime_states",
    "sensitive_keywords",
    "task_statuses",
]


def _load_yaml() -> dict[str, list[str]]:
    """最小化 yaml loader。Phase 0 决定不引入 PyYAML 作为测试依赖
    (pyproject 决议保持 deps 为空)。当前 yaml 形状很小(顶层 key
    → list-of-strings),自研一个解析器足够;后续如果需要更丰富的
    解析能力可以再切到 PyYAML。
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
            # `version: 1` 这种顶层 scalar —— parity 不关心。
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

    # 占位:Python 侧还没有可 import 的常量模块。
    # 后续会替换成形如:
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
    """sanity 检查:Java 侧必须提供 sdk-shared-constants.yaml。非 xfail。"""
    assert _YAML_PATH.is_file(), (
        f"Expected Lane P shared-constants yaml at {_YAML_PATH}. "
        "Lane P did not merge or the path moved — check docs/api/."
    )


def test_yaml_covered_keys_present() -> None:
    """sanity 检查:Java parity 断言的所有 key 都必须在 yaml 里。"""
    yaml = _load_yaml()
    missing = [k for k in _COVERED_KEYS if k not in yaml]
    assert not missing, (
        f"sdk-shared-constants.yaml missing keys {missing}. "
        "Java SharedConstantsParityTest will fail; fix yaml first."
    )


if __name__ == "__main__":  # pragma: no cover
    sys.exit(pytest.main([__file__, "-v"]))
