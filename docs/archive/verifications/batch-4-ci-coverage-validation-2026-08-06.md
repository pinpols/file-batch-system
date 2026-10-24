# Batch 4 验收报告

日期：2026-08-06
分支：`feature/batch4-ci-coverage-20260806`

## 结论

Batch 4 本地验证通过，可以进入提交和 PR 门禁阶段。

## 本批次变更

- 为 `PreprocessStep` 的流式对象判定补充边界测试，覆盖空模板、Excel/二进制、预处理流水线、压缩和加密配置。
- 为超大 Excel 输入补充私有临时文件路径测试。
- 为 `PrivateTempFiles` 补充首次创建临时根目录、文件和目录的测试。
- 为 Java SDK `ShellAtomicHandler` 补充临时根目录不存在时的私有目录创建测试。
- 将 `label-automerge` 改为依据显式 required checks 判断，并同时监听 `pr-gate`、`codeql`、`full-ci-gate`；移除旧的分支保护 API 查询和管理员绕过合并。
- 同步更新 CI 运维说明。

## 验证结果

### Maven 全量非 E2E

执行：

```text
./mvnw -B clean test org.jacoco:jacoco-maven-plugin:0.8.14:report --projects '!batch-e2e-tests'
```

结果：

- `BUILD SUCCESS`
- Tests：`4208`
- Failures：`0`
- Errors：`0`
- Skipped：`30`
- JaCoCo XML：`13` 份

### SonarQube

执行：

```text
./scripts/dev/sonar-scan.sh --skip-build
```

结果：

- Quality Gate：`OK`
- New code coverage：`83.0%`，门槛 `80%`
- New code violations：`0`
- New duplicated lines density：`0.0%`
- 未解决的新代码问题：`0`
- 全局 coverage：`63.8%`，属于历史代码基线，不作为本批次新代码门禁失败依据

本次 Sonar 导出目录：

```text
reports/sonar/2026-08-06_07-39-45/
```

### 工作流与脚本静态校验

- `actionlint`：通过
- `python3 scripts/ci/check-empty-checks.py --base origin/main`：通过
- `git diff --check`：通过

## 剩余说明

- 本次 Maven 命令明确排除了 `batch-e2e-tests`；端到端测试仍按项目既有独立门禁执行。
- 测试日志中的故障注入、连接失败和安全 bypass 警告来自测试场景，不代表本批次出现测试失败。
- 合并前仍需等待远程 PR 的 `static-checks`、`unit-it-a`、`unit-it-b1`、`unit-it-b2`、`security-scan` 全部成功。
