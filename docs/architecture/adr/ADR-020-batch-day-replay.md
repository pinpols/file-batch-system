# ADR-020 · 批量日维度重放（batch_day_replay_session）

- **Status**: Accepted（Stage 2 schema 已落 V110；后续 Stage 3-8 按本 ADR 排期）
- **Date**: 2026-05-06（Accepted: 2026-05-06）
- **Supersedes**: —
- **Related**: ADR-017（result_version，本 ADR 强依赖；版本路由全部走它）/ ADR-018（跨日 DAG，重放下游联动）/ §14.3.2（设计层缺口）/ `RerunRequest`（已有单 instance 重跑入口）

## 背景

运维场景里"重放整个 bizDate"是高频但目前不存在的能力：

- 上游补数：T 日数据被发现错了，重跑 T 日全部受影响 job；
- 监管复盘：要重新生成 2026-05-04 的全套报表（日终 + 监管口径）并显式把它们 promote 成新的 EFFECTIVE；
- Schema fix：worker 修了 bug，要把 T 日所有用错算法的产物重生成。

**当前能力**：

- 单 instance：`POST /api/console/jobs/{id}/rerun` 可触发；`RerunRequest.resultPolicy` 已暴露版本意图（ADR-017）；
- 批量重放：**完全空白** — 运维要写 SQL 拉 instance 列表 + 循环 POST，无审批、无进度、无版本一致性、无下游联动；
- 跨 job 一致性：手动循环时，部分 rerun 成功 / 部分失败，半截烂摊子要人工兜。

**强依赖** ADR-017：版本主模型不存在时，重放出来的多份 SUCCESS 仍然是隐式约定；只有 result_version 落地，"哪一份是 official"才有归属。

## 决策（提案）

引入 **batch_day_replay_session** 一等聚合：

- session 描述"重放 (tenantId, calendarCode, bizDate) 的某子集 jobs"的请求 + 进度 + 结果；
- session 内部把每个 job 的重跑委托给现有 `RerunRequest` 通道；
- 配审批、版本策略、下游联动、回滚回退；
- console-api 暴露完整生命周期 + 审批 UI。

### 核心模型

```
batch.batch_day_replay_session
  id                  BIGSERIAL PK
  tenant_id           VARCHAR(64)  NOT NULL
  calendar_code       VARCHAR(128) NOT NULL
  biz_date            DATE         NOT NULL
  scope               VARCHAR(32)  NOT NULL  -- ALL / ALL_FAILED / SUBSET_JOB_CODES / OUTPUTS_ONLY
  scope_payload       JSONB                  -- SUBSET_JOB_CODES 时存 [jobCode...]
  result_policy       VARCHAR(64)  NOT NULL  -- 同 RerunRequest.resultPolicy，传给每个子 rerun
  config_version_policy VARCHAR(64) NOT NULL
  config_version      INTEGER                -- USE_SPECIFIED_VERSION 时用
  reason              VARCHAR(1024) NOT NULL -- 重放原因，必填，审计用
  approval_id         BIGINT                 -- 审批流 FK
  status              VARCHAR(32)  NOT NULL  -- PENDING_APPROVAL / RUNNING / SUCCEEDED / PARTIAL_FAILED / CANCELLED
  total_count         INTEGER                -- 应重跑 instance 数
  succeeded_count     INTEGER NOT NULL DEFAULT 0
  failed_count        INTEGER NOT NULL DEFAULT 0
  in_flight_count     INTEGER NOT NULL DEFAULT 0
  requested_by        VARCHAR(128) NOT NULL
  approved_by         VARCHAR(128)
  started_at          TIMESTAMPTZ
  completed_at        TIMESTAMPTZ
  trace_id            VARCHAR(128)
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  UNIQUE (tenant_id, calendar_code, biz_date, status) -- 见"不变量"
```

```
batch.batch_day_replay_entry        -- session 内每个 job 的重跑入口
  id                BIGSERIAL PK
  session_id        BIGINT NOT NULL
  tenant_id         VARCHAR(64) NOT NULL
  job_code          VARCHAR(128) NOT NULL
  source_instance_id BIGINT             -- 上一次产生 EFFECTIVE 的 instance；OUTPUTS_ONLY 模式下用
  rerun_instance_id  BIGINT             -- 本次 rerun 创建的新 instance；填回
  status            VARCHAR(32) NOT NULL -- PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED
  failure_reason    VARCHAR(1024)
  started_at        TIMESTAMPTZ
  finished_at       TIMESTAMPTZ
  result_version_id BIGINT             -- 本次产生的 result_version（ADR-017）
  UNIQUE (session_id, tenant_id, job_code)
  INDEX  (session_id, status)
```

### scope 语义

| scope | 重放集合 | 适用 |
|---|---|---|
| `ALL` | 当日所有 SUCCESS / FAILED 的 job_instance | 监管复盘 |
| `ALL_FAILED` | 当日 FAILED / PARTIAL_FAILED | 故障日补救 |
| `SUBSET_JOB_CODES` | scope_payload 列出的 jobCode | 局部 bug fix |
| `OUTPUTS_ONLY` | 不重跑，只把指定历史 instance 提升为 EFFECTIVE | 已有版本 promote 的批量入口 |

### 状态机

```
PENDING_APPROVAL ──(approve)──► RUNNING ──(全部完成)──► SUCCEEDED
       │                            │                        │
       │                            ├──(部分失败)──► PARTIAL_FAILED
       │                            │
       └──(reject)─► CANCELLED      └──(ops cancel)─► CANCELLED
```

`RUNNING` 期间禁止启动同 (tenantId, calendarCode, bizDate) 的另一个 session — 见"不变量"。

### 启动流程

1. **POST /api/console/batch-day-replays**（带 BatchDayReplaySubmitCommand）→ 服务端：
   - 校验 scope 合法、result_policy 与 ADR-017 contract 对齐；
   - 解析当日 instance 列表 → 写 batch_day_replay_session + entries（status=PENDING）；
   - 若需要审批 → status=PENDING_APPROVAL；否则直接 RUNNING。
2. **审批通过**（或免审）→ session.status=RUNNING；
3. **派发**：BatchDayReplayDispatcher 轮询 RUNNING session 的 PENDING entries，逐一调用：
   ```java
   rerunService.rerun(RerunRequest.builder()
       .tenantId(...)
       .jobInstanceId(entry.getSourceInstanceId())
       .resultPolicy(session.getResultPolicy())
       .configVersionPolicy(session.getConfigVersionPolicy())
       .configVersion(session.getConfigVersion())
       .replaySessionId(session.getId())   // 透传，写到 rerun_policy_snapshot
       .reason("BATCH_DAY_REPLAY:" + session.getReason())
       .build());
   ```
4. **回填**：`job_instance` 终态阶段（已经会写 result_version, ADR-017）→ 检查 `replay_session_id` 标签 → 反查 entry → 更新 entry.status / rerun_instance_id / result_version_id；session 计数 +1；
5. **收敛**：所有 entry 终态 → session.status = SUCCEEDED / PARTIAL_FAILED；触发完成通知。

### OUTPUTS_ONLY 模式

不创建新 instance，直接对历史 SUCCESS 的 result_version 做 promote：

```sql
-- 伪代码
UPDATE result_version SET status='EFFECTIVE', effective_at=now() WHERE id=:targetVersionId;
UPDATE result_version SET status='SUPERSEDED', deactivated_at=now() WHERE id=:oldEffectiveId;
```

复用 ADR-017 的 promotion API；entry.status 直接 SUCCEEDED。

### 下游联动

ADR-018 的 `WAITING_DEPENDENCY` 节点会因为上游 EFFECTIVE 切换被 reconciler 唤醒。session 完成后还会发一条 domain event `batch_day_replay_completed`，让有兴趣的下游（外部 BI、监管报送）订阅。

### 接审批 / 权限点

- `batch_day_replay.read`：列表 / 详情；
- `batch_day_replay.write`：发起请求；
- `batch_day_replay.approve`：审批通过（必须配置高于 .write 的角色）；
- `batch_day_replay.cancel`：运行中取消；
- `batch_day_replay.outputs_only`：OUTPUTS_ONLY 模式额外校验（直接改 EFFECTIVE 风险高）。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 2 张新表 + archive 镜像；`job_instance` 已经在 ADR-017 阶段加了 `replay_session_id`（如未加，本 ADR 阶段补） |
| 模块 | console-api 加 BatchDayReplay controller/service；orchestrator 加 dispatcher + reconciler；rerun 路径接 replay_session_id 透传 |
| 兼容性 | 不依赖 ADR-017 不能开放（强依赖）；旧 RerunRequest 单实例路径不变 |
| 性能 | session 派发按 batch_size + rate_limit_per_min 限速，避免一次 1000 个 rerun 压垮 dispatcher |
| 观测 | 新 metric: `batch.replay.session.{active,succeeded,failed}` / `batch.replay.entry.{pending,running,terminal}` |

## 实施分阶段

| Stage | 范围 | 估算 | 守护 |
|---|---|---|---|
| 1 | ADR-017 落地 | (前置) | (前置) |
| 2 | schema（session + entry + archive 镜像 + job_instance.replay_session_id） | 2 天 | flyway + archive 对齐 |
| 3 | `BatchDayReplayService.submit / approve / cancel`；权限点 + 审批接入 | 3 天 | service 单测 + IT |
| 4 | `BatchDayReplayDispatcher` 轮询派发；rate_limit；rerun 路径透传 replay_session_id | 3 天 | 派发 IT + 限速测 |
| 5 | terminal 回填；session 状态推进；完成事件 outbox | 2 天 | 回填 IT |
| 6 | OUTPUTS_ONLY 模式（promote-only） | 2 天 | promote 守护测 |
| 7 | console UI（非后端） | (UI 团队) | — |
| 8 | E2E：上游补数 → ALL scope replay → 下游 ADR-018 节点唤醒 | 2 天 | E2E 测试 |

总：~14-15 人天（不含 ADR-017）。

## 替代方案（被拒绝）

| 方案 | 拒绝原因 |
|---|---|
| 单纯提供"批量 rerun 脚本" | 没有审批、没有进度、跨 job 失败半截无收尾；也无法接 ADR-017 的版本策略一致 |
| 用现有 `compensation_request` 重用 | compensation 是局部失败补偿语义，不是日级一致重放；语义混淆 |
| 让运维直接写 SQL 改 result_version.status | 绕过审批 / 审计 / 重跑的产物校验，最危险 |

## 不变量

1. **同 (tenantId, calendarCode, bizDate) 同时只能有 1 个 active session**（PENDING_APPROVAL / RUNNING）—— DB 部分唯一索引保证；
2. session 内 entries 的 result_policy 必须与 session 一致（不允许子项漂移）；
3. `OUTPUTS_ONLY` scope 不创建新 job_instance，仅做 result_version promote；其他 scope 必走 RerunRequest；
4. session 完成（SUCCEEDED / PARTIAL_FAILED / CANCELLED）后不可重新激活，重放需新建 session；
5. session 的所有 result_version 写入必须走 ADR-017 主模型，不允许直写业务表；
6. cancel 只能停止还未开始派发的 entries；已 RUNNING 的 instance 不强杀（让它跑完，按结果回填）。

## 验收标准

- 单测：`BatchDayReplayServiceTest`（4 种 scope × 状态机分支 × 权限点）
- IT：active session 防并发提交；entries 派发 + rate_limit；OUTPUTS_ONLY promote
- E2E：监管复盘场景（ALL scope + MANUAL_CONFIRM_EFFECTIVE policy）跑通，下游 ADR-018 节点正确唤醒
- 守护：`ReplaySessionUniquenessInvariantTest` 强制断言唯一 active session

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | 失败 entry 的重试 | **v1 不做 session 内重试**。失败 entry 留 FAILED 终态，运维按需对该 entry 的 `source_instance_id` 走单 instance `RerunRequest`（不挂 session）。v2 再考虑 session 内 retry-failed-entries API；现在做会让"session 进度"语义和"entry 历史"耦合 |
| 2 | 跨日 replay | **不支持单 session 跨日**。Console 循环创建 N 个单日 session（保持 session 一对一 (tenant, calendarCode, bizDate) 不变量）。前端可加"跨日重放向导"批量提交，但持久层每日仍独立 session |
| 3 | 并发上限 | `batch.replay.dispatch.parallelism = 10`（默认，可配）；上限 50 防 dispatcher 压垮 worker 池。同时套 `batch.replay.dispatch.rate-limit-per-min = 60`（默认）做令牌桶节流 |
| 4 | 回滚 | **不特设 rollback API**。误 promote 走"反向 OUTPUTS_ONLY session"把旧版本切回 EFFECTIVE — 走同样的审批 + 审计链；rollback 本身可被审计 / 重放 |
| 5 | 大批 instance | session 创建按 `batch.replay.session.entry-batch-insert-size = 500` 分块 INSERT；entry 数 > `entry-async-threshold`（默认 5000）时 status=PENDING 派 outbox 事件让 reconciler 异步建剩余 entries。session 在 entry 全建完前 status 暂为 `PENDING_APPROVAL`（PENDING_APPROVAL 期不需要 entry 全建好，approval 时 lazy 校验）|

### 不会做（以及原因）

- ❌ **不支持 session 内 entry 级 retry / cancel** —— v1 整 session 是原子审批单元，单 entry 失败不重启 session
- ❌ **不让 session 跨 (tenant, calendarCode, bizDate) 唯一性** —— 不变量 #1 是基线，跨日重放就开多个 session
- ❌ **不暴露"force-cancel running entry"API** —— 已 RUNNING 的 instance 让它跑完按结果回填，session cancel 只停未派发的 entries（不变量 #6）
- ❌ **不允许直接 SQL 改 result_version.status 实现"快速 promote"** —— 必走 ADR-017 promotion API + 本 ADR 的 OUTPUTS_ONLY session
- ❌ **不在 v1 做 session 间链式编排**（"replay session A 完成后自动起 session B"）—— 需要时由 console / 外部编排器拉起

### 实施触发条件

ADR-020 已经在 §14.3.2 列为后端缺口，**触发条件已满足**（监管复盘 / 上游补数都是已知场景），按下方排期推进。Stage 1（ADR-017 落地）已开工（参见 commit `172074e2` 等），Stage 2 schema 已在 V110 落地。

### 不变量再确认（与 V110 schema 对齐）

V110 落地的约束已守护本 ADR 的 5 条不变量：

| 不变量 | 守护方式 |
|---|---|
| 1. 同 (tenant, calendarCode, bizDate) 至多 1 个 active session | `uk_replay_session_active` partial unique index `WHERE status IN ('PENDING_APPROVAL', 'RUNNING')` |
| 2. entry result_policy 与 session 一致 | entry 不存 result_policy，dispatcher 永远从 session 读，物理上无法漂移 |
| 3. OUTPUTS_ONLY 不创建新 instance | dispatcher 路由分支 + service-level guard，IT 必须覆盖 |
| 4. 终态 session 不可重新激活 | service `submit / approve / cancel` 校验 status；DB 通过 `ck_replay_session_status` + 应用层一起守 |
| 5. result_version 写入必走 ADR-017 主模型 | 入口收敛在 worker output → ADR-017 promote 链；session 自身不持有写入能力 |
| 6. cancel 只停未派发 entries | dispatcher 进入 RUNNING 前 check entry / session status，已 RUNNING 自然按结果回填 |
