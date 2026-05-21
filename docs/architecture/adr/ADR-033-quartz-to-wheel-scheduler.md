# ADR-033: Quartz 调度器替换为时间轮(HashedWheelTimer)

- 状态: **Accepted(暂缓实施)** (2026-05-21) — 决策成立,实施触发条件未达
- 范围: `batch-trigger` 模块的调度引擎(当前 Quartz JDBC JobStore)
- 影响: trigger 模块整体重写调度子系统 + DB schema 迁移(quartz_* 表 → 自管 `trigger_runtime_state`)+ 灰度切换 + 双引擎并行

## 范围边界

✅ **做**:在 trigger 模块用 Netty `HashedWheelTimer` 替换 Quartz JDBC JobStore;ShedLock 单 leader + 滑动窗口 5min 预扫;DB 强约束 `UNIQUE(trigger_id, scheduled_fire_time)` 防双 fire;onLeaderAcquire fast-path 立即扫窗口防 failover 冷启动延迟。

❌ **不做**:替换 Quartz 的 CronExpression 解析器(继续直接复用 `org.quartz-scheduler:quartz` 库的 `CronExpression`);自研时间轮(直接用 Netty 久经生产);把 Wheel 暴露成业务可见 API(对外仍是 trigger fire 语义)。

---

## 1. 背景 / 现状

### 现状

- trigger 模块用 Quartz + JDBC JobStore 存调度状态(`qrtz_*` 11 张表)
- 单 trigger 实例 + 数据库锁(Quartz `QRTZ_LOCKS`)做 leader 选举
- 业务量 < 100 万 fire/天 时无压力

### 真实驱动力(为什么记 ADR)

> ❌ **不是**「Quartz 现在跑不动」。当前业务量 Quartz 性能完全够用,**没有任何生产事件归因到 Quartz**。
>
> ✅ **是**两个**预见性**问题 + 一个**架构债务**:
>
> 1. **业务量级拐点**:接近 **1000 万 fire/天** 时 Quartz JDBC JobStore 的 `QRTZ_LOCKS` 表会成单点瓶颈(行锁高频争抢 + 长事务 + scheduler thread starvation),业界多个案例(Pulsar / Dubbo / Curator 弃用 Quartz)印证
> 2. **failover 时延不可控**:Quartz 的 misfire detection 走 misfire policy + 长 polling,leader 切换后冷启动可达 30s+,期间任务全 delay
> 3. **debug 成本高**:Quartz JDBC schema(qrtz_*)字段语义晦涩,oncall 排障要查 `QRTZ_TRIGGERS` / `QRTZ_FIRED_TRIGGERS` / `QRTZ_LOCKS` 三表 join,认知负担重

### 替代方案对比(已 evaluate)

| 选项 | 工程量 | 成熟度 | 跳过中间态? |
|---|---|---|---|
| Quartz 现状 | 0 | 生产级 | — |
| Quartz 集群模式(多实例 + DB JobStore) | 0.3 人月 | 生产级 | 还是 Quartz,瓶颈未除 |
| Quartz 独立库(Galaxy 等) | 2 人月 | 中 | **中间过渡,不推荐** |
| **Netty HashedWheelTimer**(本 ADR) | **1.5-2 人月开发 + 1 人月灰度** | **生产级**(Pulsar / Dubbo / Curator) | ✅ 一步到位 |
| 自研时间轮 | 4+ 人月 | 低 | 重造轮子,拒 |

---

## 2. 决策

### 用 Netty HashedWheelTimer 替换 Quartz,跳过 Quartz 集群模式中间态

技术核心:

- **时间轮 tick** = 100ms(批处理 SLA 够用,实时交易场景不适用 — SLA 矩阵见 [`quartz-replacement-design.md`](../quartz-replacement-design.md) §4)
- **leader 选举**:ShedLock(已在用)选 1 trigger 实例为 leader,只 leader 推 trigger 到时间轮
- **滑动窗口预扫**:每分钟扫"未来 5 min 内将 fire"的 trigger 推到内存时间轮;内存 dedup set 防重复 push
- **fire 强约束**:DB 加 `UNIQUE(trigger_id, scheduled_fire_time)` 兜底,GC pause + 锁过期场景应用层幂等防不住的双 fire 由 DB 兜
- **failover fast-path**:`onLeaderAcquire` 立即扫一次窗口,不等下一个 minute tick,避免冷启动 30s+ delay
- **cron 解析**:**继续用 Quartz 库的 `CronExpression`**,不自研(已是事实标准,跟当前 trigger 语义零差异)

### 实施分阶段

| 阶段 | 内容 | 工期 | 触发条件 |
|---|---|---|---|
| **Phase 0** | 评估完成,本 ADR + design + evaluation 文档就位 | ✅ 已完成 | — |
| **Phase 1** | Wheel SDK + DB schema(`trigger_runtime_state` + UNIQUE 约束)+ 单测 | 0.5 人月 | 业务量逼近 500 万 fire/天 |
| **Phase 2** | 双引擎并行(Quartz 主 + Wheel 影子跑)+ metric 对比 | 0.5 人月 | Phase 1 完成 |
| **Phase 3** | staging 灰度 1 minor | 0.5 人月 | Phase 2 metric 差异 < 0.1% |
| **Phase 4** | 生产灰度 → 全量切换 → 删 Quartz 代码 + qrtz_* 表 | 0.5 人月 | staging 全量稳定 1 周 |

---

## 3. 暂缓实施的理由(2026-05-21)

**本 ADR 状态为 Accepted(暂缓实施)**。决策本身成立,但**实施触发条件未达**:

1. **业务量未到拐点**:当前 fire QPS 远低于 1000 万/天,Quartz 在 P99 latency / DB lock contention / failover time 三项指标上都未呈现 degradation
2. **没有生产事件归因 Quartz**:近 12 月 oncall 记录 0 起调度延迟 / 双 fire / failover 卡 30s 的事件
3. **机会成本高**:1.5-2 人月开发 + 1 人月灰度 = 真实 2-3 个月推进周期,该窗口期投入到 DBA 分区 / ADR-010 灰度 / 软删除推广 等"已发生的债"性价比更高
4. **复杂度风险**:替换调度核心是高风险动作,在没有量化驱动时启动等于"为了改而改",违反 ADR 范围纪律

### 触发实施的条件(满足任一即启 Phase 1)

- [ ] trigger fire QPS 持续 4 周 > 500 万/天(50% 拐点预警)
- [ ] 一次生产事件根因归到 Quartz(双 fire / leader 切换延迟 / `QRTZ_LOCKS` 死锁)
- [ ] DB ops 反馈 `qrtz_*` 表行锁等待时长进入 P99 > 100ms 红线
- [ ] 业务方明确提出 fire 时间精度 SLA 要求(Quartz 默认 ±10s)

---

## 4. 决策代价(Consequences)

### 正面

- **架构简化**:删 `qrtz_*` 11 张表,trigger 模块 schema 收敛到 1 张 `trigger_runtime_state`,oncall 排障路径直观
- **性能上限**:Pulsar / Dubbo 验证可支撑亿级 fire/天,远超业务可见上限
- **failover 可观察**:`onLeaderAcquire` 立即扫窗口 + metric 暴露,SLA 可量化(目标 < 5s)
- **依赖收敛**:`org.quartz-scheduler:quartz` 仍保留(用其 `CronExpression`),但 `quartz-jdbc-jobstore` 链路全删

### 负面

- **2-3 个月实施周期**:实施期 trigger 模块代码量翻倍(双引擎并行)
- **DB 迁移成本**:`qrtz_*` 11 张表数据需迁移到 `trigger_runtime_state`,需 cutover 维护窗口(ADR-010 的 SOP 模板可复用)
- **新故障域**:Netty 内存时间轮 + ShedLock 协作引入新的 failure mode,需补充集成测试 + 故障注入
- **学习曲线**:团队 Quartz 经验失效,新故障定位需基于内存时间轮 + Netty 视角

### 中性

- **CronExpression 复用 Quartz 库**:仍依赖 Quartz jar 一个类(`org.quartz.CronExpression`),无需自研也无需 migrate cron 语法

---

## 5. 关联文档

- [`docs/architecture/quartz-replacement-evaluation.md`](../quartz-replacement-evaluation.md) — 战略层评估(为什么换、何时换的 5 项风险兜底校准)
- [`docs/architecture/quartz-replacement-design.md`](../quartz-replacement-design.md) — 战术层实施(详细架构 + 5 项风险兜底 + Pre-flight Checklist)
- [`docs/runbook/quartz-capacity-baseline.md`](../../runbook/quartz-capacity-baseline.md) — 当前 Quartz 容量基线测算
- [`scripts/db/quartz-replacement-preflight-scan.sql`](../../../scripts/db/quartz-replacement-preflight-scan.sql) — 切换前 SQL 预扫(cron 兼容性 / fire 唯一约束验证)
- [`docs/analysis/todo-master.md`](../../analysis/todo-master.md) §三-E — Quartz 切换 backlog 7 项(staging/ops 配合)
- [`docs/analysis/hardening-backlog.md`](../../analysis/hardening-backlog.md) — 暂缓状态登记

## 6. Revisit Cadence

每季度 review 一次本 ADR 的"触发实施条件"满足度。trigger fire QPS 数据走 `batch.trigger.fire.total` Prometheus counter,看 P95/P99/peak 三档。
