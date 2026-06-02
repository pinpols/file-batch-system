"""冒烟测试:包能正确导入且 version 元数据合理。

Phase 0 还没有业务逻辑可测 —— 这两个断言只用来证明构建链
(hatchling -> pip install -e -> pytest)接通。
"""

from __future__ import annotations

import re

import batch_worker_sdk


def test_package_imports() -> None:
    """无副作用地 import 顶层包。"""
    assert batch_worker_sdk is not None


def test_version_is_pep440() -> None:
    """``__version__`` 必须是符合 PEP 440 归一化、能发布的字符串。"""
    version = batch_worker_sdk.__version__
    assert isinstance(version, str)
    # 宽松 PEP 440 形状:N(.N)*([abc|rc]N)?,对 Phase 0 来说够用。
    assert re.match(r"^\d+\.\d+\.\d+([abc]|rc)?\d*$", version), version
