#!/usr/bin/env bash
# Test Impact Analysis (TIA) POC — bash 入口,封装 python 实现。
#
# 用法:
#   TIA_BASE=origin/main TIA_HEAD=HEAD scripts/ci/select-affected-tests.sh
#
# 输出 stdout:逗号拼接的测试类 simple name,直接喂给:
#   mvn verify -Dtest="$(scripts/ci/select-affected-tests.sh)" -DfailIfNoTests=false
#
# 当前为方案 B(自建静态依赖图):
#   - 业界候选 `io.github.teyckmans:test-impact-analysis-maven-plugin`
#     在 Maven Central 不存在(2026-05 验证 repo1.maven.org/.../maven-metadata.xml 404,
#     central.sonatype.com 搜索无结果)。
#   - 该插件 GitHub 仓库亦 404,无活跃维护。
#   - 回退用 python 扫 import 列表反向索引 + 同 package simple-name 匹配
#     + 5 跳传递闭包,见 select-affected-tests.py。
#
# 这是 POC 脚本,**不接入 pr-gate.yml**,待人工 review 后再决定是否启用。
# 详见 docs/runbook/tia-poc-2026-05-22.md。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY_IMPL="$SCRIPT_DIR/select-affected-tests.py"

# 逐行 → 逗号
python3 "$PY_IMPL" | paste -sd, -
