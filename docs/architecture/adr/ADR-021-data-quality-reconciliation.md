# ADR-021 · 数据对账闭环（Data Quality / Reconciliation）

- **Status**: Accepted（**两档路径**：v0.0 mini 草案 ~3.5-5 天 待激活 / v1.0 完整 ~16-18 天 强金融触发；**默认两档都不开工** — 见"实施触发条件"+ §实施分阶段）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-012（失败分类，DQ 失败映射 DATA_QUALITY）/ ADR-017（result_version，DQ 通过才能 EFFECTIVE）/ ADR-020（重放，DQ 失败可走 OUTPUTS_ONLY 反向 promote）/ §14.3.2 / [ADR 012/021-027 优先级 + 范围边界](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)

## 范围边界（Scope Discipline）

> **本 ADR 的边界红线：只做"批量交付闭环对账"，不扩张为"企业数据治理 / 财务核算 / 主数据 / 数据血缘"平台。**
>
> **判定提问**：「这条规则的失败结果，是修业务数据还是裁定业务对错？」修业务数据 → 属本 ADR；裁定业务对错 → 不属本 ADR。

| ✅ 做（批量交付闭环） | ❌ 不做（避免做成数据治理平台） |
|---|---|
| 文件条数 / 金额 / hash / 分区批次对账 | 主数据治理 / 数据血缘 / 全域数据质量平台 |
| 上游交付清单 vs 实际接收 | 财务总账核算规则 / 风控量化裁定 |
| 下游发送清单 vs 实际成功回执 | 跨系统业务语义仲裁 |
| 借贷平衡 / 跨表 sum / 跨日连续性 | DQ 失败"自动修复"（auto-fix 反保守原则） |
| 差异单生成 + 人工确认状态 | BLOCKER 一键禁用 ops 后门（要绕过必经 MANUAL_APPROVAL 留痕） |
| 4 类规则 + 3 档 severity + EFFECTIVE gate | 跨租户共享规则（按租户隔离 + 模板库做最佳实践） |

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

> **三档路径**：v0.0 mini（行级持久化 + ADR-017 hook，3-5 天，待激活）→ v1.0 完整版（4 类规则 + DSL，16-18 天，强金融触发）。**默认两档都不开工**，等触发信号到才走。

### v0.0 mini — 待激活草案（3-5 人天）

> **触发条件（mini 启动前置）**：≥ 1 次"读到 SUCCESS 但是数错的数据"投诉 / 业务方主动诉求"我想看哪些 instance 数据有质量问题"。

#### 范围对比 v1.0

| 项 | v0.0 mini | v1.0 完整 |
|---|---|---|
| 行级校验持久化 | ✓ | ✓ |
| ROW_LEVEL 规则 | ✓（复用现成 ImportDataQualityService）| ✓ |
| TABLE_LEVEL 规则 | ✗ 业务方自写 SPI 写回 | ✓ 平台 DSL |
| CROSS_TABLE 规则 | ✗ 业务方自写 SPI 写回 | ✓ 平台 DSL |
| CROSS_DAY 规则 | ✗ | ✓ 接 ADR-018 BizDateArithmetic |
| ADR-017 PENDING gate hook | ✓（BLOCKER 失败 → 不进 EFFECTIVE）| ✓ |
| 受限 SQL DSL 解析器 | ✗ | ✓ |
| 规则 CRUD 控制台 | ✗ | ✓ |
| 规则模板库 | ✗ | ✓ |

#### v0.0 mini 实施 Stage

| Stage | 范围 | 估算 | 触点 |
|---|---|---|---|
| 0-A | V117 `data_quality_check` 表 + archive 镜像（schema 只到 v0.0 字段，无 rule_id 外键 — 留 NULL 扩展位） | 0.5 天 | flyway + ArchiveSchemaDriftCheck 注册 |
| 0-B | `DataQualityCheckEntity` + `DataQualityCheckMapper` + xml（insert / selectByJobInstance / selectByCalendarBizDate） | 0.5 天 | 单测 |
| 0-C | `ImportDataQualityService` 失败路径增补：除了现有 `data_error` 还往 `data_quality_check` 写一条 `rule_type=ROW_LEVEL, severity=WARN/BLOCKER` 汇总行 | 1 天 | IT |
| 0-D | 业务方 SPI：`DataQualityCheckSink` 接口（业务方自己跑跨表 SQL，把结果通过 sink 写到 `data_quality_check`）| 0.5 天 | 接口 + 1 个示例实现 |
| 0-E | ADR-017 `ResultVersionWriter` 接 BLOCKER gate：终态时查 `data_quality_check`，有任意 BLOCKER `status=FAIL` 行 → result_version `promotion_policy = MANUAL_APPROVAL` | 1 天 | 单测 + 与 ADR-017 IT 联动 |
| 0-F | Console 列表查询：`GET /api/console/queries/data-quality-checks?bizDate=&instanceId=` 只读视图 | 0.5 天 | controller 层只读 |
| 0-G | 守护：`DataQualityCheckSinkSafetyTest` 强制 sink 不允许直接写业务表 | 0.5 天 | 静态守护 |

总 ~3.5-5 人天。

#### v0.0 mini 边界（写死）

| ✅ v0.0 做 | ❌ v0.0 不做（v1.0 才做） |
|---|---|
| `data_quality_check` 一张表 + 行级 / 业务方写回结果 | SQL DSL 解析器 / 受限 SQL 执行器 |
| BLOCKER → ADR-017 强制 MANUAL_APPROVAL | 平台帮业务方跑 SQL 规则 |
| Console 只读视图列表 | 规则 CRUD / 编辑器 / 模板库 |
| 业务方 SPI 写回（`DataQualityCheckSink`）| CROSS_DAY / 业务日历联动 |
| 单测 + 静态守护防写业务表 | E2E 银行场景三件套（借贷 / 跨表 / 期初期末）|

#### v0.0 mini → v1.0 升级路径

mini 落地后如果出现：

- ≥ 2 次"业务方写 SPI 太麻烦，我们想让平台跑 SQL"诉求；
- 出现金融场景客户 / 监管要求；
- 业务方在 SPI 里把"对账规则"散落到各个 worker → 平台失去统一治理；

→ 启动 v1.0 完整版。mini 的 schema 已经预留了 `rule_id` 列（NULL 即业务方 SPI 写回），v1.0 加上 `data_quality_rule` 表 + DSL 解析器即可，不破坏 v0.0 数据。

### v1.0 完整版（强金融触发）

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

### v0.0 mini 主链路影响评估

| 路径 | 影响 |
|---|---|
| trigger 路径 | 零 |
| launch / dispatch 路径 | 零 |
| worker claim / execute 路径 | 零（IMPORT worker 增 1 行 INSERT，与 data_error 同事务）|
| worker REPORT 路径 | 零 |
| orchestrator 终态推进 | **小** —— ResultVersionWriter 增 1 次 `selectByJobInstance` 查 BLOCKER；预期 < 5ms（部分索引 `WHERE severity='BLOCKER' AND status='FAIL'`）；零 BLOCKER 时直接命中索引返回 |

mini 与 ADR-022 v0.1 同档：**主链路侵入极小**，仅终态推进路径多 1 次 hot-path 索引查询。

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

**两档分别 gated**：

### v0.0 mini 触发（任一即可，~3.5-5 人天）

1. **下游投诉**：出现客户 / 产品方"我读到了一份 SUCCESS 但是数错的数据"投诉 ≥ 1 次；
2. **业务方诉求**：业务方主动要求"我想看哪些 instance 数据有质量问题"运维视图；
3. **行级校验外溢**：`ImportDataQualityService` 失败行被业务方手工 SQL 反复查，平均每周 ≥ 3 次。

### v1.0 完整触发（任一即可，~16-18 人天）

1. **金融场景**：用于银行 / 证券 / 保险类批量（事实条件，强 P0）；
2. **对账类业务**：项目里出现 ≥ 2 个 "B 表数 ≠ A 表数" 故障复盘；
3. **mini 不够用**：v0.0 mini 落地后，业务方写 `DataQualityCheckSink` SPI 散落到 ≥ 3 个 worker，平台失去统一治理；
4. **监管 / 合规**：明确要求"数据质量必须独立审计 / 平台统一管控"。

**非金融 + 无下游一致性诉求 + 无业务方写回 SPI 诉求时，两档都暂不开工。**

### v0.0 mini schema 草案（V117 待激活）

```sql
-- 待激活：触发条件命中再 commit，先在 ADR 留草案防漂移
CREATE TABLE IF NOT EXISTS batch.data_quality_check (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    job_instance_id    BIGINT       NOT NULL,
    rule_id            BIGINT,                    -- v0.0 留 NULL（业务方 SPI 写回）
                                                  -- v1.0 引用 data_quality_rule.id
    rule_type          VARCHAR(32)  NOT NULL,     -- ROW_LEVEL（v0.0）/ TABLE_LEVEL / CROSS_TABLE / CROSS_DAY（v1.0）
    rule_code          VARCHAR(128),              -- 业务方自起，便于 UI 检索
    severity           VARCHAR(16)  NOT NULL,     -- BLOCKER / WARN / INFO
    status             VARCHAR(16)  NOT NULL,     -- PASS / WARN / FAIL / SKIPPED
    metrics_json       JSONB,                     -- 命中数 / 比例 / 偏差等
    failure_sample     JSONB,                     -- 前 N 条失败样本（≤ 64KB）
    error_message      VARCHAR(2048),
    checked_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, job_instance_id, rule_code)  -- 同 instance 同规则结果幂等
);

ALTER TABLE batch.data_quality_check ADD CONSTRAINT ck_dq_check_rule_type
    CHECK (rule_type IN ('ROW_LEVEL', 'TABLE_LEVEL', 'CROSS_TABLE', 'CROSS_DAY'));
ALTER TABLE batch.data_quality_check ADD CONSTRAINT ck_dq_check_severity
    CHECK (severity IN ('BLOCKER', 'WARN', 'INFO'));
ALTER TABLE batch.data_quality_check ADD CONSTRAINT ck_dq_check_status
    CHECK (status IN ('PASS', 'WARN', 'FAIL', 'SKIPPED'));

-- 部分索引：BLOCKER + FAIL 走 hot path（ResultVersionWriter 终态查这个）
CREATE INDEX idx_dq_check_blocker_fail
    ON batch.data_quality_check (tenant_id, job_instance_id)
    WHERE severity = 'BLOCKER' AND status = 'FAIL';

-- archive 镜像（保 ArchiveSchemaDriftCheck 通过）
CREATE TABLE archive.data_quality_check_archive
    (LIKE batch.data_quality_check INCLUDING ALL);
```

字段对 v1.0 全兼容：v1.0 加 `data_quality_rule` 表 + `rule_id` 引用即可，无破坏性 ALTER。

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
