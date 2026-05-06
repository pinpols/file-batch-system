# ADR-017 · 结果版本（result_version）主模型

- **Status**: Proposed
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-009（workflow 节点 output 上报机制）/ ADR-018（跨批量日 DAG 依赖，依赖本 ADR 取上游 EFFECTIVE 版本）/ ADR-020（批量日维度重放，依赖本 ADR 做版本路由）/ §14.3.2（设计层缺口）

## 背景

当前重跑（`RerunRequest` 暴露 `resultPolicy ∈ {CREATE_NEW_VERSION, KEEP_BOTH, MANUAL_CONFIRM_EFFECTIVE}` + `configVersionPolicy`）只在 API 入参 + `rerun_policy_snapshot` JSON 落库；**底层多版本结果模型并不存在**：

- 同一 `(tenantId, jobCode, bizDate)` 重跑后会产生 ≥ 2 行 `instance_status='SUCCESS'` 的 `job_instance`，下游消费"用哪份"靠隐式约定（最大 `run_attempt`、最晚 `finished_at`）；
- worker 的实际产物（`fileId` / `recordCount` / `outputs:Map`）目前只散落在 `file_record` / `pipeline_step_run` / `workflow_node_run.output` 等业务表上，没有"哪一份现在生效"的稳定锚点；
- 监管复盘场景"哪一份数字被发到监管"现在只能靠 commit log + 人盯，无审计闭环；
- ADR-009 已经让 worker 上报 `outputs:Map<String,Object>`，落到 `workflow_node_run.output`，但只局限于 workflow 内部串联，没有"job_instance 维度的产物 + 版本"主模型。

## 决策（提案）

引入独立 **result_version** 主模型，与 `job_instance` 解耦但 1:N 关联。

### 核心模型

```
batch.result_version
  id                 BIGSERIAL PK
  tenant_id          VARCHAR(64) NOT NULL
  business_key       VARCHAR(256) NOT NULL  -- 见下"业务主键定义"
  version_no         INTEGER NOT NULL       -- 同一 business_key 内单调递增
  job_instance_id    BIGINT NOT NULL        -- 产生此版本的实例
  status             VARCHAR(32) NOT NULL   -- PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED
  effective_at       TIMESTAMPTZ            -- 推到 EFFECTIVE 的时刻；NULL 表示还未生效
  deactivated_at     TIMESTAMPTZ            -- 被新版本取代的时刻；NULL = 仍在线
  payload_storage    VARCHAR(32) NOT NULL   -- INLINE_JSON / EXTERNAL_REF / FILE_RECORD
  payload_json       JSONB                  -- INLINE_JSON 时直接放
  payload_ref        VARCHAR(512)           -- EXTERNAL_REF 时放 OSS / file_record 指针
  generated_at       TIMESTAMPTZ NOT NULL
  generated_by       VARCHAR(128)           -- system / operatorId
  promotion_policy   VARCHAR(32) NOT NULL   -- AUTO_LATEST / MANUAL_APPROVAL
  approval_id        BIGINT                 -- 关联 approval_request, MANUAL_APPROVAL 才用
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
  UNIQUE (tenant_id, business_key, version_no)
  INDEX  (tenant_id, business_key, status)
  INDEX  (tenant_id, business_key, effective_at DESC) WHERE status='EFFECTIVE'
```

### 业务主键定义（business_key）

约定字符串拼接（避免单独建多列索引）：

| 场景 | business_key | 例 |
|---|---|---|
| 普通 job | `job:{jobCode}:{bizDate}` | `job:DAILY_PNL:2026-05-04` |
| Workflow job | `workflow:{workflowCode}:{bizDate}` | `workflow:EOD_SETTLE:2026-05-04` |
| 分区级（细粒度） | `job:{jobCode}:{bizDate}:p={partitionKey}` | `job:RPT_DETAIL:2026-05-04:p=BR_001` |

`business_key` 由 worker 上报或 orchestrator 计算，写入 `result_version` 时落库；下游消费按此 key 反查 EFFECTIVE 版本。

### 状态机（version.status）

```
PENDING ─────► EFFECTIVE ─────► SUPERSEDED ─────► ARCHIVED
   │              ▲                │
   │              │                │
   └────► (rejected/废弃)          └──► (新版本上线触发)
```

- **PENDING**：刚创建，等待审批 / 等待 ops 显式 promote（`promotion_policy=MANUAL_APPROVAL` 时停在这里）
- **EFFECTIVE**：当前生效版本，consumer 看的就是它；同一 business_key 至多 1 行 EFFECTIVE
- **SUPERSEDED**：被新版本取代，但仍可查询（审计 / 回滚）
- **ARCHIVED**：超过保留期，payload 可被 GC 回收（保留元数据行）

### 版本创建路径

| 触发 | 默认 promotion_policy | 行为 |
|---|---|---|
| 首次跑（无前置版本） | `AUTO_LATEST` | 创建 v1 → 立即 EFFECTIVE |
| 重跑 `resultPolicy=CREATE_NEW_VERSION` | `AUTO_LATEST` | 创建 vN+1 → 立即 EFFECTIVE，旧 EFFECTIVE → SUPERSEDED |
| 重跑 `resultPolicy=KEEP_BOTH` | `MANUAL_APPROVAL` | 创建 vN+1 → PENDING，旧 EFFECTIVE 不变；ops 显式选才切换 |
| 重跑 `resultPolicy=MANUAL_CONFIRM_EFFECTIVE` | `MANUAL_APPROVAL` | 同上，但强制走 approval flow |

### Worker output 绑定

复用 ADR-009 已有的 `TaskExecutionReportDto.outputs:Map<String,Object>`；orchestrator 在 job_instance 终态（SUCCESS/PARTIAL_SUCCESS）时统一调用 `ResultVersionWriter`：

```java
// orchestrator 任务终态阶段
ResultVersionWriter.write(
    tenantId, businessKey, jobInstanceId,
    outputs,          // worker 上报的 Map
    promotionPolicy   // 由 rerun_policy_snapshot 决定
);
```

`payload_storage` 决定 outputs 怎么落：

- **INLINE_JSON**（默认 < 1MB）：直接 `payload_json`
- **EXTERNAL_REF**（> 1MB 或显式声明）：worker 自行写 OSS，`payload_ref=oss://bucket/key`，version 行只存指针
- **FILE_RECORD**：worker 已写 `file_record`，version 行 `payload_ref=file_record:{id}`，复用现有文件治理

### Consumer 读取契约

1. **业务表 SQL** —— 不再扫 `job_instance`，统一查 `result_version`：
   ```sql
   SELECT payload_json, payload_ref, payload_storage
     FROM batch.result_version
    WHERE tenant_id = ? AND business_key = ? AND status = 'EFFECTIVE';
   ```
2. **应用层 API** —— 新增 `ResultVersionQueryService.findEffective(tenantId, businessKey)`，统一缓存 + Optional 返回；
3. **Workflow 跨节点** —— ADR-009 的 `$.nodes.X.output.fileId` 引用层不变，底层从 `workflow_node_run.output` 平移到 `result_version` 由 resolver 透明完成。

### GC / 保留策略

每个 jobDefinition / 全局可配：

```
batch.result-version.retention.superseded-days   = 90
batch.result-version.retention.archived-days     = 365
batch.result-version.retention.scan-cron         = "0 30 3 * * *"
```

- SUPERSEDED 超过 N 天 → ARCHIVED（payload 移 cold storage 或 NULL）
- ARCHIVED 超过 M 天 → 物理删除（保留 audit log）
- 当前 EFFECTIVE 永不删

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 1 张新表 + 1 个 archive 镜像表（按 §archive 冷表对齐 红线）+ retention scheduler |
| 模块 | orchestrator 加 `ResultVersionWriter` / `ResultVersionQueryService`；console-api 加版本视图 + promote/archive ops；worker 不变（继续上报 `outputs`） |
| 业务消费 | 所有现在直接 join `job_instance` 拿成功记录的 SQL 都要切换到 result_version；提供视图 `v_job_latest_effective` 做兼容期过渡 |
| 兼容性 | 历史 SUCCESS 实例需要回填 v1 EFFECTIVE 行（migration 脚本） |
| 性能 | EFFECTIVE 部分索引（`WHERE status='EFFECTIVE'`）保证 hot path < 1ms；冷查询走 (tenant_id, business_key) 复合索引 |

## 实施分阶段

| Stage | 范围 | 估算 | 守护 |
|---|---|---|---|
| 1 | schema（result_version + archive 镜像）+ migration 回填 v1 + ArchiveSchemaDriftCheck 注册 | 2 天 | flyway 跑通；archive 对齐测试 |
| 2 | `ResultVersionWriter` 在 task 终态阶段写入；`payload_storage=INLINE_JSON` 单一路径先打通 | 3 天 | 单测 + IT |
| 3 | `ResultVersionQueryService` + EFFECTIVE 缓存 + 读路径替换（保留兼容视图） | 2-3 天 | E2E 跨重跑测试 |
| 4 | console-api 版本列表 / promote / archive；MANUAL_APPROVAL flow 接 approval_request | 3 天 | 接审批守护测试 |
| 5 | retention scheduler + payload_ref 扩展（OSS / file_record） | 2 天 | scheduler 单测 |

总：~12-13 人天。

## 替代方案（被拒绝）

| 方案 | 拒绝原因 |
|---|---|
| 直接给 `job_instance` 加 `is_effective` 列 | job_instance 表已经 30+ 列，再加版本语义会和状态机互相污染；ARCHIVED 数据无法和热数据隔离 |
| 用 `job_instance.run_attempt` 隐式版本号 | 没有 promote / approval / GC 概念，无法支持 KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE |
| 业务表自己维护版本列 | 每张业务表重复实现一次状态机；orchestrator 拿不到统一审计入口 |

## 不变量

1. 同一 `(tenant_id, business_key)` 内最多 1 行 `status=EFFECTIVE` —— DB 部分唯一索引保证；
2. `effective_at` 单调推进，旧 EFFECTIVE 必须先 SUPERSEDED 才能让出；切换在同事务里完成；
3. ARCHIVED 行的 `payload_json` 可为 NULL，但 `id` / `version_no` / `business_key` 永不删（审计需要）；
4. `result_version.job_instance_id` 永远引用 SUCCESS / PARTIAL_SUCCESS 终态实例，不引用 RUNNING；
5. worker 不直接写 result_version，永远只通过 orchestrator —— 维持单一状态主机。

## 验收标准

- 单测：`ResultVersionWriterTest`（4 种 promotion_policy × 4 种状态机分支）
- IT：重跑两次同一 (jobCode, bizDate)，CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE 三种策略下 EFFECTIVE 数据稳定
- E2E：跨重跑场景下游 SQL 拿到的 payload 始终对应 EFFECTIVE 版本
- 守护：`ResultVersionEffectivenessInvariantTest` 强制断言"同 business_key 至多 1 行 EFFECTIVE"

## 开放问题

1. **细粒度（partition 级版本）**：是否需要 partition-level result_version？目前先做 job-level，partition 留 placeholder（business_key 的 `:p={partitionKey}` 后缀做扩展点）；
2. **跨 job 引用**：jobA 消费 jobB 输出时，怎么显式指定要哪个版本？倾向 ADR-018（跨批量日依赖）里给 workflow_node 加 `consume_version_strategy ∈ {EFFECTIVE_ONLY, LATEST_INCLUDING_PENDING, SPECIFIC_VERSION}`；
3. **payload 大小阈值**：1 MB 的 INLINE→EXTERNAL 切换点是否合适？需要看真实负载分布，先按 1 MB 起，再调；
4. **跨租户**：现阶段每个 tenant 各自隔离 business_key，未来跨租户共享场景（例如平台级聚合）暂不在本 ADR 范围。
