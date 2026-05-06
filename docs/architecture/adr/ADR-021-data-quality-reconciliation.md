# ADR-021 · 数据对账闭环（Data Quality / Reconciliation）

- **Status**: Accepted（实施 gated — 见"实施触发条件"）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-012（失败分类，DQ 失败映射 DATA_QUALITY）/ ADR-017（result_version，DQ 通过才能 EFFECTIVE）/ ADR-020（重放，DQ 失败可走 OUTPUTS_ONLY 反向 promote）/ §14.3.2

## 背景

当前系统验证只到"job 跑成功"层 — `job_instance.instance_status = SUCCESS` 即可触发下游。但**跑成功 ≠ 数据对**：

- **借贷不平衡**：分录借方 sum ≠ 贷方 sum，job SUCCESS 但账错；
- **跨表对账失效**：A 表汇总 ≠ B 表明细 sum，下游报表全错；
- **跨日不连续**：昨日尾值 ≠ 今日初值，发现时已经丢了一天交易；
- **行级数据非法**：金额负数 / 字段缺失，进表后污染下游。

worker 内有 `ImportDataQualityService` 做导入行级校验（`error_threshold_pct` / `error_count` 阈值），但局限：

1. 只在 IMPORT worker 路径（PROCESS / EXPORT / WORKFLOW 没有）；
2. 只看行级 reject，不做表级 / 跨表 / 跨日校验；
3. 失败行写 `data_error` 表，但**不阻塞 result_version EFFECTIVE**；下游消费仍读到部分坏数据。

业界（Soda / Great Expectations / Delta Live Tables）把 DQ 当一等公民：job 完 → DQ gate → promote。

## 决策

引入独立 `data_quality_check` 主模型 + DQ 规则 DSL + ADR-017 EFFECTIVE 链路前置 gate。

### 核心模型

```sql
batch.data_quality_rule         -- DQ 规则定义
  id              BIGSERIAL PK
  tenant_id       VARCHAR(64)
  rule_code       VARCHAR(128)
  rule_type       VARCHAR(32)       -- ROW_LEVEL / TABLE_LEVEL / CROSS_TABLE / CROSS_DAY
  scope_business_key VARCHAR(256)   -- 关联到 result_version.business_key
  expression      TEXT NOT NULL     -- DSL: SQL / JSONLogic / Java SPI 引用
  threshold_json  JSONB             -- {maxFailRows, failRatio, deltaTolerance...}
  severity        VARCHAR(16)       -- BLOCKER / WARN / INFO
  enabled         BOOLEAN
  UNIQUE (tenant_id, rule_code)

batch.data_quality_check         -- 一次 job 的 DQ 检查实例
  id              BIGSERIAL PK
  tenant_id       VARCHAR(64)
  job_instance_id BIGINT
  rule_id         BIGINT
  status          VARCHAR(16)       -- PASS / WARN / FAIL / SKIPPED
  metrics_json    JSONB             -- 命中数 / 比例 / 偏差等
  failure_sample  JSONB             -- 前 N 条失败样本
  checked_at      TIMESTAMPTZ
  INDEX (tenant_id, job_instance_id)
```

### 规则类型

| rule_type | 例 | 谁写 |
|---|---|---|
| `ROW_LEVEL` | `amount > 0 AND currency IN ('CNY','USD')` | 业务方 / 复用现有 ImportDataQualityService |
| `TABLE_LEVEL` | `SELECT count(*) FROM staging WHERE biz_date=:bizDate` >= 阈值 | DBA |
| `CROSS_TABLE` | `sum(detail.amt) = (SELECT total FROM summary)` | 数据团队 |
| `CROSS_DAY` | `today.opening_balance = yesterday.closing_balance` | 业务方 |

### Gate 链路

```
job_instance terminal (SUCCESS / PARTIAL_FAILED)
    │
    ▼
DataQualityCheckExecutor — 取 (tenant, business_key) 关联的 enabled rules
    │
    ├── 全部 PASS / 仅 WARN / INFO    → result_version 写入 EFFECTIVE / AUTO_LATEST 流程
    │
    └── 任一 BLOCKER FAIL              → result_version.promotion_policy 强制 MANUAL_APPROVAL
                                         job_instance.failure_class = DATA_QUALITY (即使 SUCCESS)
                                         alert ERROR + 等 ops 显式 promote / reject
```

### DSL（受限 SQL）

`expression` 字段支持：

- **静态 SQL**：`SELECT count(*) AS cnt FROM business.staging WHERE biz_date=:bizDate`
- **占位符**：`:bizDate / :tenantId / :jobInstanceId / :prevBizDate`（`BizDateArithmetic` 解 prev 业务日）
- **比较算子**：`metrics.cnt >= 1000` / `abs(a - b) <= 0.01`

禁 DDL / DML / `pg_*` 系统表；专用只读 DataSource pool 隔离。

### Console / Audit

- 规则 CRUD：`POST /api/console/dq/rules`，权限 `dq.write`；
- 检查结果：`GET /api/console/dq/checks?bizDate=...&tenantId=...`；
- 失败样本：`failure_sample` JSONB 限制前 50 条 + 字节上限 64KB；
- 写 `audit_log`（运维改规则即审计）。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 2 张新表 + archive 镜像；`result_version` 的 EFFECTIVE 路径加 1 个 gate；规则 DSL 引入限制性 SQL 解析层 |
| 模块 | orchestrator 加 `DataQualityCheckExecutor`；console-api 加规则 CRUD；现有 `ImportDataQualityService` 收编为 ROW_LEVEL 规则的实现之一 |
| 性能 | 简单规则 < 50ms；CROSS_TABLE 查 staging 几秒级，配合 `result_version` 异步 promote 不阻塞业务路径 |
| 兼容性 | 无规则的 job 完全不走 gate（与现有行为一致）；启用对每个 job 独立配置 |

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | schema + DQ rule CRUD + 简单 ROW_LEVEL（迁移 ImportDataQualityService） | 3 天 |
| 2 | TABLE_LEVEL + CROSS_TABLE（受限 SQL 执行器 + 占位符解析） | 4 天 |
| 3 | DataQualityCheckExecutor 嵌入 result_version EFFECTIVE 链路 | 3 天 |
| 4 | CROSS_DAY（接 ADR-018 BizDateArithmetic） | 2 天 |
| 5 | Console UI（规则编辑器 + 检查结果视图，非后端） | UI 团队 |
| 6 | 规则模板库（常见银行场景 5-10 套预设） | 2 天 |
| 7 | 守护 + E2E | 2 天 |

总 ~16-18 人天（不含 UI）。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 让业务表自己加 trigger / check constraint | 散落、版本化困难、跨表对账无能为力 |
| 引入完整 Great Expectations / Soda 工具链 | 重资产、需独立 Python runtime、与 Java 生态阻抗大 |
| 把 DQ 嵌入 worker 业务代码 | 与 worker 解耦原则冲突；规则修改要发版 worker |

## 不变量

1. DQ 规则永远不写业务表，只读 + 检查；
2. BLOCKER 规则失败必须阻断 EFFECTIVE 切换；不允许"已知失败但还是 promote"的 silent path（要 promote 必经 MANUAL_APPROVAL）；
3. 检查必须幂等：同 (job_instance_id, rule_id) 多次执行结果稳定（除非业务数据变了）；
4. CROSS_DAY 只读 EFFECTIVE 版本（不读 PENDING / SUPERSEDED）；
5. 规则 DSL 永远只读（DDL / DML 编译期拒绝）。

## 验收

- 单测：4 种 rule_type 各 3 个典型 case
- IT：BLOCKER 失败阻断 result_version EFFECTIVE；CROSS_DAY 接 BizDateArithmetic 跨节假日
- E2E：银行日终典型对账（借贷平衡 + 跨表 sum + 期初期末连续）三件套跑通
- 守护：`DataQualityRuleSqlSafetyTest` 强制 DSL 不允许 DDL/DML

## 实施触发条件

满足任一：
1. **金融场景**：用于银行 / 证券 / 保险类批量（事实条件，强 P0）；
2. **对账类业务**：项目里出现 ≥ 2 个 "B 表数 ≠ A 表数" 故障复盘；
3. **下游消费方**：出现客户/产品方"我读到了一份 SUCCESS 但是数错的数据"投诉。

非金融 + 无下游一致性诉求时本 ADR 暂不开工。

## 开放问题（已收口）

| # | 问题 | 决策 |
|---|---|---|
| 1 | DSL 选 SQL 还是 JSONLogic 还是 SpEL | 三层并存：ROW_LEVEL 用 JSONLogic（简洁可视化）；TABLE/CROSS 用 SQL（业务方熟）；复杂逻辑走 Java SPI 接口（少数特例） |
| 2 | DQ 失败的样本要存多久 | 与 result_version retention 同步 — SUPERSEDED 90 天 → ARCHIVED 365 天 → 物理删（保留 metrics_json 元数据） |
| 3 | 跨租户共享规则 | 不做。规则按租户隔离；提供"规则模板库"做平台级最佳实践 |
| 4 | DQ check 失败是否自动重跑 job | 不做。DQ 失败对应 ADR-012 的 `DATA_QUALITY` failure class，policy = NONE retry；要重跑走 ADR-020 replay 显式触发 |

### 不会做

- ❌ 不替业务方写规则（平台只提供 DSL + runtime）
- ❌ 不在 v1 做"DQ 失败自动修复"（auto-fix 是反保守原则的）
- ❌ 不允许 BLOCKER 规则被 ops 一键禁用 — 真要绕过走 MANUAL_APPROVAL 留痕
- ❌ 不在 worker 内做跨表 / 跨日 DQ —— 单 worker 视角看不到全量
