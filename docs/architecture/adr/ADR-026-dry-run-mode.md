# ADR-026 · 演练 / Dry-run 模式

- **Status**: Accepted（实施 gated — 见"实施触发条件"）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: §配置开关规范（与 `bypass-mode` 区分）/ ADR-020（重放，dry-run 是 replay 的另一面）/ §14.3.2

## 背景

不同语义的"安全跑"现在都被 `batch.security.bypass-mode` 一个开关糊在一起：

- **bypass-mode**：本地联调用，整条安全链放行 — 不安全，prod 拒绝；
- **dry-run**：prod 也想跑，但**不写业务表 / 不发外部消息**，只演练；当前不存在。

业务诉求：

1. **大版本升级演练**：升级前一周想跑一次"模拟日终批"看资源占用 / 时长 / 命中率，但不真改账；
2. **新加 job 上线**：新 job 第一次跑想看跑成什么样、消耗多少配额，确认无误再开 ENFORCE；
3. **灾备切换演练**：备机房接管后跑一遍验链路，但不能真的发外部清算指令；
4. **配置变更前预演**：calendar / quota / cron 改完，想看下次 fire 会怎样，再点 enable。

业界（Spring Batch test mode、Airflow `--dry-run`、银行 UAT 完全镜像 prod）都把 dry-run 当独立维度，**不是 bypass**。

## 决策

引入 `dry_run` 一等字段，与安全 / 鉴权 / 多租隔离正交：

- 三种粒度：**job 级** / **workflow 级** / **batch_day 级**；
- worker SDK 提供 `DryRunGuard`，所有写业务表 / 外部 IO 必经；
- result_version 不创建 EFFECTIVE / 不发 outbox event；
- 但该写的 audit / metric / log 都写（就是要看跑出来啥）。

### 模型

```sql
ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE batch.workflow_run
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;
```

`LaunchRequest` 加 `dryRun: boolean` 字段，沿透到 instance / partition / task。

### Dry-run 行为契约

| 行为 | dry_run = false | dry_run = true |
|---|---|---|
| job_instance 创建 | ✓ | ✓（带 dry_run=true） |
| partition / task 创建 | ✓ | ✓ |
| worker 拉 task 执行 | ✓ | ✓ |
| worker 读业务表 | ✓ | ✓（只读不变） |
| worker 写业务表 / staging | ✓ | ✗ DryRunGuard 拦 |
| worker 写 file_record | ✓ | ✗（写 stub 行带 `dry_run=true`） |
| worker 调外部 API（结算 / 发邮件） | ✓ | ✗ 短路返回 mock |
| outbox_event 写入 | ✓ | ✗（不发下游） |
| result_version 创建 | ✓ | ✓ status=DRY_RUN（不进 EFFECTIVE 链） |
| audit log / metric / trace | ✓ | ✓（关键 — 演练就是要看这些） |
| job_instance 终态 | SUCCESS / FAILED | SUCCESS_DRY_RUN / FAILED_DRY_RUN |

### Worker SDK：DryRunGuard

```java
@Component
public class DryRunGuard {
  public <T> T executeOrSkip(boolean dryRun, ExternalActionType type, Supplier<T> real) {
    if (dryRun) {
      log.info("[DRY-RUN] skipped {} type={}", real, type);
      metric.increment("worker.dry_run.skipped", "type", type.name());
      return mockResult(type);
    }
    return real.get();
  }
}
```

step plugin 写业务表 / 外部 IO 前必经此 guard；不经 = 静态 lint 工具拦（CI 守护）。

### 触发路径

1. **Console UI**：launch 表单加 "Dry Run" toggle；权限 `job.launch.dry_run` 与正常 launch 分开；
2. **API**：`POST /api/console/jobs/launch` body 加 `dryRun: true`；
3. **Batch_day 级**：`POST /api/console/batch-days/dry-run` 启动整日演练（创建 dry_run batch_day_instance + 跑全部当日 job 的 dry_run 副本）；
4. **CLI**：`mvn ... -Dbatch.launch.dry-run=true`（开发自测）。

### 终态状态机

```
job_instance.instance_status:
  ...                                # 现有终态不变
  + SUCCESS_DRY_RUN                  # dry_run 跑通
  + FAILED_DRY_RUN                   # dry_run 跑挂
```

ck_constraint 加 dry_run 终态；**SUCCESS_DRY_RUN 不被任何下游消费认为"等于 SUCCESS"**（明确隔离）。

### Result_version 与 dry_run

ADR-017 result_version 加 `status = DRY_RUN`：

- 不计入 EFFECTIVE 单一约束；
- 保留 `payload_json` / `metrics_json` 给 ops 看演练结果；
- retention 比正常 SUPERSEDED 短（默认 7 天）。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 3 张表加 `dry_run` 列 + ck_constraint 加 2 个终态；result_version status enum 加 DRY_RUN |
| 模块 | LaunchRequest / orchestrator 全链路透传；worker SDK 加 DryRunGuard；step plugin 接入；console launch 表单 + 权限 |
| 性能 | dry_run 不写业务表 / 不发外部 IO，比正常跑快；但 audit / metric 全写故 PG 写入压力近似 |
| 兼容 | 老 instance dry_run = false 默认；老终态不变；新终态老 console 渲染要更新 |

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | schema + LaunchRequest 字段 + 透传 | 2 天 |
| 2 | DryRunGuard SDK + step plugin SPI | 3 天 |
| 3 | result_version DRY_RUN status + 不进 EFFECTIVE | 1 天 |
| 4 | 终态 SUCCESS_DRY_RUN / FAILED_DRY_RUN | 1 天 |
| 5 | step plugin 接入（IMPORT/EXPORT/PROCESS/DISPATCH 各路径) | 5 天（散在多 worker） |
| 6 | Console UI + 权限点 | 2 天 |
| 7 | CI 守护：lint 检查 step plugin 必经 DryRunGuard | 1 天 |
| 8 | E2E：典型日终批 dry-run vs real | 2 天 |

总 ~17 人天（worker 端散，主要工作量在 step plugin 接入）。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 完全镜像 UAT 环境跑 | 数据 / 配置 / topic 全要 mirror，运维成本巨大；同时 prod 上的演练诉求依然解不了 |
| 让 worker 内部加 if-else 判断 dry_run | 散乱、漏改风险高；统一 Guard 才能 lint 兜底 |
| 复用 bypass-mode | 语义不同：bypass 是"放行不安全"，dry-run 是"安全但不副作用"；混在一起易误用 |

## 不变量

1. dry_run = true 的 instance 终态永远是 SUCCESS_DRY_RUN / FAILED_DRY_RUN，**不能**是 SUCCESS / FAILED；
2. 下游消费（result_version 读 / workflow_node 跨日依赖）**永远不读** DRY_RUN 版本；
3. dry_run = true 的 outbox_event 不写库（不仅仅是 PUBLISHED 状态过滤）—— 物理不写，永远不会发到 Kafka；
4. `bypass-mode` 与 `dry_run` 正交且互不替代：bypass-mode=true + dry_run=false 仍会真改业务表（只是不安全）；bypass-mode=false + dry_run=true 不改但要鉴权；
5. dry_run 跑出来的 metric tag 都带 `dry_run=true`，看板默认过滤掉，演练才显式打开。

## 验收

- 单测：DryRunGuard 在 4 种 ExternalActionType 下行为
- IT：完整 IMPORT 跑 dry-run 不写 staging 表 / 不发 outbox / 不调 dispatch
- E2E：日终批 batch-day 级 dry-run vs real 资源使用对比
- CI 守护：lint 工具扫所有 step plugin 写业务表 / 外部 IO 必经 DryRunGuard

## 实施触发条件

满足任一：
1. **大版本升级前演练**：项目里出现"想升级但怕出事，求安全演练手段"诉求；
2. **新业务上线**：新 job_definition 第一次跑想测算负载 + 一致性；
3. **灾备演练**：DR 切换演练成为常态（每季度 / 半年）；
4. **客户合同要求**：UAT-like 演练能力是合同明确条款。

短期看不到上述场景就不开工；占住这个 ADR 编号即可。

## 开放问题（已收口）

| # | 问题 | 决策 |
|---|---|---|
| 1 | dry_run 写 staging 还是 mock | **mock**：dry_run 不写任何业务侧表（包括 staging）；只写 audit / metric / result_version |
| 2 | 跨 job 依赖：dry_run jobA 的 output 给 dry_run jobB 用还是 real jobA | **dry_run 链 vs real 链分离**：dry_run jobB 只读 dry_run jobA 的 output（DRY_RUN result_version）；不允许 dry_run 消费 EFFECTIVE 历史数据（避免污染演练） |
| 3 | 是否支持"实跑后回滚"作为 dry-run 的替代 | **不做**。回滚不可靠（业务表 + 外部 IO 已改），dry-run 是物理不写的根本保证 |
| 4 | DryRunGuard 漏接的兜底 | CI lint 必须扫强制；运行期再加 audit "step 内是否调过非 guard 包装的 IO" |

### 不会做

- ❌ 不让 dry_run 跑出的 result_version 自动 promote（永远不变 EFFECTIVE）
- ❌ 不允许 mixed mode（同一 job 内一部分 step dry 一部分 real），整 instance 必须一致
- ❌ 不复用 bypass-mode 开关
- ❌ v1 不做 "diff" 工具（dry-run vs real 差异比较），用 metric / audit 各自看
