# ADR-043 · 依赖感知 fire —— 触发前上游就绪闸

- **Status**: Proposed
- **Date**: 2026-06-20
- **Related**: `docs/analysis/system-wide-capability-gap-analysis-2026-06-20.md`(全系统缺口分析,本 ADR 的源,缺口 #13/#15)、`docs/plans/settlement-gap-remediation-roadmap-2026-06-20.md`(Phase 4.2)、ADR-010(trigger 异步解耦)、ADR-018(跨日依赖,**边界对照**)、ADR-023(日历依赖,**边界对照**)、ADR-027(范围红线)
- **Plan**: 本 PR 仅设计文档,评审定方向后再逐 PR 落地。架构级:改触发侧 fire 时序、引入 trigger→orchestrator 只读就绪查询,**先拍板边界**。

## 范围边界(实施 PR 必答)

判定提问:**「这是『上游没跑完 / 文件没到,就先别 fire』的就绪闸,还是『裁定上游业务结果对不对』?」** 就绪闸(fire 与否)→ 属本 ADR;裁定业务对错 → ❌ 不属(ADR-021 红线)。

**做(✅)**:
- ✅ 触发**前**检查所声明的上游是否就绪(上游 `job_code` 在**同批次日**已 `SUCCESS` / 上游文件组已 `TRIGGERED`),未就绪 → **defer(不 fire,留待重评)** 而非盲 fire。
- ✅ 依赖声明落在 **trigger 侧配置**(触发器声明 `depends_on`),粒度 = `job_code + 批次日对齐策略(SAME_DAY / PREV_DAY)`。
- ✅ 复用既有 misfire 机制:超过容忍窗口仍不就绪 → 走既有 `trigger_misfire_pending` + misfire 策略(NONE/AUTO/MANUAL_APPROVAL),**不新增第 4 张同义事件表**。
- ✅ 就绪查询走 orchestrator **只读 internal API**(`/internal/...`),trigger 不直连 orchestrator 状态表。

**❌ 不做(明确边界)**:
- ❌ **不裁定上游业务结果对错**(ADR-021 红线):只问「上游这个 job 这个批次日**跑成功了没 / 文件到了没**」,不问「上游产出的数据业务上对不对」。
- ❌ **不替代 DAG 内依赖**(ADR-018 跨日依赖 / ADR-023 日历依赖):那些是 **workflow 节点间**(已 launch 之后,DAG 推进时)的依赖;本 ADR 是 **trigger fire 之前**(还没 launch)的就绪闸,**正交叠加**,不合并、不改 DAG 语义。
- ❌ **不引入 trigger 直写/直读 orchestrator 状态表**(违反读写分离与模块边界):就绪只经 orchestrator 暴露的只读 internal API。
- ❌ **不做跨租户依赖**:依赖只在同租户内声明。

## 背景

缺口分析(2026-06-20)触发侧 Top 缺口 #1:**依赖感知 fire(MISSING)**。现状(`HashedWheelTriggerScheduler` / `QuartzLaunchJob`)是**纯时间触发**——cron 到点即 `doFire`,**不看上游就绪**:

1. 结算链路常是「源系统出文件 → 我方导入 → 加工 → 出账」,若上游(源文件 / 上游 job)还没好,纯时间 fire 会**拉起一个注定无输入 / 半输入的批**,要么空跑、要么基于不完整数据产出**错误结算结果**,级联污染下游。
2. 现有依赖能力都在 **DAG 内**:`CrossDayDependencyResolver`(ADR-018,workflow 节点等上游批次日 job)、`CalendarDependencyEntity`(ADR-023,日历间 WAIT_SETTLED/WAIT_CUTOFF)。这些都发生在**已 launch 之后**的 workflow 推进期——**管不到「该不该 launch」这一层**。
3. `TriggerType.EVENT` 占位但无消费者(见 ADR-044 兄弟项 4.4),文件到达也不能反向 gate 时间触发。

结果:一个时间触发器无法表达「我每天 02:00 想跑,但**前提是上游 SETTLE_JOB 今天已成功**;没成功就别拉起、到 04:00 还没好按 misfire 处置」。这是结算级级联风险。

## 决策

在 trigger fire 路径上,`doFire` **之前**插入一道**上游就绪闸**:

### 1. 依赖声明(trigger 侧配置)

触发器定义增可选 `dependsOn`(JSONB,落 `trigger_definition` 或并表 `trigger_dependency`,实施期定):

```json
{
  "dependsOn": [
    { "kind": "JOB", "code": "UPSTREAM_SETTLE_JOB", "bizDateAlign": "SAME_DAY" },
    { "kind": "FILE_GROUP", "code": "SRC_FILES", "bizDateAlign": "SAME_DAY" }
  ],
  "readinessWindow": "PT2H",          // 容忍等待窗口,超时走 misfire
  "onTimeout": "MISFIRE"              // 复用既有 misfire 策略
}
```

- `kind=JOB`:上游 `job_code` 在对齐后的批次日 `job_instance` 终态为 `SUCCESS`(或 `SUCCESS_DRY_RUN` 视配置)。
- `kind=FILE_GROUP`:上游文件组在该批次日 `file_arrival_group` 状态 = `TRIGGERED`(全到齐)。
- `bizDateAlign`:`SAME_DAY`(同批次日)/ `PREV_DAY`(上一批次日,跨日链路)。

### 2. fire 前就绪查询(只读 internal API)

`HashedWheelTriggerScheduler.doFire`(及 Quartz 路径)在调用 `TriggerService.launchScheduled` **之前**:

- 若触发器无 `dependsOn` → 行为不变(直接 fire),**向后兼容、默认无依赖**。
- 若有 `dependsOn` → 调 orchestrator 新增只读端点 `GET /internal/readiness?tenantId=&bizDate=&deps=...`,返回各依赖 `READY/PENDING`。
  - 全 `READY` → 正常 fire。
  - 有 `PENDING` 且未超 `readinessWindow` → **defer**:不 fire,不前移 `next_fire_time`,标记一条 pending-readiness,留待下一扫描窗重评(类似 misfire pending 但语义是「等就绪」)。
  - 超 `readinessWindow` 仍 `PENDING` → 落 `trigger_misfire_pending`,走既有 misfire 策略(AUTO 补跑 / MANUAL_APPROVAL 等)。

### 3. 边界与一致性

- trigger **只读** orchestrator 就绪状态,经 internal API,**不直连状态表**(守读写分离 + 模块边界)。
- orchestrator 仍是唯一状态主机;就绪判定基于其权威 `job_instance` / `file_arrival_group`。
- 幂等不变:fire 仍经 `trigger_request(tenant,dedup_key)` + outbox + 下游三层去重。

## 影响

- **正向**:结算级级联风险大降——上游没好不会拉起错误批;「等就绪窗 + 超时 misfire」让晚到上游有容忍,又不无限等。
- **新增**:trigger→orchestrator 一条只读 internal 依赖(`/internal/readiness`);trigger 扫描每窗对有依赖的触发器多一次就绪查询(可批量、可缓存短 TTL)。
- **向后兼容**:无 `dependsOn` 的触发器零行为变化(绝大多数)。
- **风险**:就绪查询失败/超时的降级策略需定义(fail-open 盲 fire vs fail-closed 不 fire);建议 fail-closed + 告警(结算宁可不 fire),实施期定。

## 备选(未采纳)

- **把依赖建进 DAG(workflow 前置 WAIT 节点)**:需把每个时间触发器包成 workflow,语义重、对纯 job 触发器过度。已有 DAG 依赖管节点间,本 ADR 专补「launch 之前」这一层,正交。
- **trigger 直查 orchestrator 状态表**:简单但破读写分离 + 模块边界,否决。
- **事件驱动替代时间触发**(上游成功发事件→下游 fire):更实时,但需全链路改造、且时间兜底仍要;留作 4.4 EVENT 消费者落地后的增强方向,与本就绪闸可叠加(事件到了即就绪)。
