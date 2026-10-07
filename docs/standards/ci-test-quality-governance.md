# CI 与测试质量治理

## 目标与边界

本规范约束 GitHub Actions 供应链、失败重试、软门禁、变更覆盖率、核心变异测试和质量趋势。目标是让失败可追踪、例外有期限、关键测试能发现实现被错误修改，而不是增加新的 CI 平台。

当前实现保持以下边界：

- 不引入外部质量数据库或自建看板，报告使用 GitHub Actions 摘要和构建产物。
- 不做全仓 PIT；只覆盖稳定、纯逻辑且承载生命周期规则的核心类。
- 不把 CodeQL 上传重试等有限传输容错误算成业务软门禁。
- 不因存量 Bundle 覆盖率较低而一次性抬高全仓阈值；新改动使用变更行阈值，存量继续使用棘轮。

## 五项约束

### 1. 外部 Action 固定

所有非本地 `uses:` 必须固定到 40 位提交 SHA，尾部保留版本注释便于 Dependabot 和人工审查。新增或升级 Action 时，应先解析目标 release/branch 的提交，再运行：

```bash
python3 scripts/ci/check-github-action-pinning.py
actionlint
```

### 2. flaky 测试隔离

必需门禁不自动重跑失败测试。只有已确认的不稳定测试可临时使用：

```java
@FlakyTest(issue = "#1234", owner = "@batch-team", expiresOn = "2026-12-31")
```

默认测试排除 `flaky` tag；每周和手动 Full Gate 单独运行隔离测试。到期项、缺少 Issue 或责任人的项由静态门禁阻断。根因修复后必须删除注解，不能改成永久 `@Disabled`。

### 3. 软门禁生命周期

软门禁登记在 [`../governance/soft-gates.json`](../governance/soft-gates.json)。每项必须声明：

- 稳定 ID 与 owner；
- 当前非阻断基线；
- `promotionDeadline`；
- 升级为硬门禁的目标。

工作流或 POM 中的软失败点必须使用 `soft-gate-id` 对应登记项。有限重试、清理和采证步骤使用 `non-gate-failure-policy` 说明，不进入软门禁清单。

### 4. 覆盖率与变异测试

PR 和 Full Gate 的 Java shard 在测试后生成 JaCoCo XML，对本 shard 本次变更的可执行行执行 80% 覆盖率门禁。注释、声明和空白等非 JaCoCo 可执行行不进入分母。

每周及手动 Full Gate 对以下类执行 PIT：

- `FileStateMachine`：文件生命周期合法迁移；
- `DefaultLifecycleEventMapper`：通用生命周期事件映射与终态保护。

PIT 当前要求覆盖率至少 80%、变异杀死率至少 70%。扩大范围前必须先证明运行时间稳定且目标类具有清晰单元测试，不把依赖容器的长链路纳入。
PIT HTML/XML 与隔离测试报告作为 `scheduled-test-quality-evidence` 保留 30 天。

### 5. 质量趋势

Full Gate 的 `quality-trend` job 汇总当前测试 XML和最近 30 次 Full Gate，输出：

- 测试数、失败、错误、跳过和累计耗时；
- 首次失败重跑数量与失败类型；
- E2E 套件成功数；
- 禁用测试和软门禁数量；
- 最近 Full Gate 成功率和平均耗时。

JSON/Markdown 产物保留 90 天。趋势用于发现退化，不替代当前提交的硬门禁结论。

## 本地验证

```bash
python3 scripts/ci/check-github-action-pinning.py
python3 scripts/ci/check-soft-gate-governance.py
python3 scripts/ci/check-flaky-test-governance.py
bash scripts/ci/run-critical-mutation.sh
```

变更行覆盖率依赖测试产生的 `target/site/jacoco/jacoco.xml`，通常由 PR/Full Gate shard 执行；本地只在已经运行对应模块测试后调用。

## 变更要求

修改本规范涉及的 POM、工作流、脚本或登记表时，应同步更新 `docs/runbook/ci.md` 和 `scripts/ci/README.md`。Action 版本升级、阈值调整、PIT 范围扩大或软门禁延期都必须在 PR 中给出运行证据和理由。
