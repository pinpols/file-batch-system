# ADR-034:CAP 定位 — 核心链路 CP,只读 / 观测层 AP

**状态**:Accepted(2026-05-30)
**Reviewer**:架构组
**关联**:[ADR-002](./ADR-002-transactional-outbox.md) outbox / [ADR-007](./ADR-007-dual-datasource.md) dual datasource / [ADR-014](./ADR-014-claim-idempotency.md) CLAIM 幂等 / [ADR-015](./ADR-015-worker-side-outbox.md) worker outbox

---

## 背景

分布式系统不能同时保 CAP 三性,**P(分区容忍)是物理必然**(网络一定会抖),实际选择在 C(一致性)和 A(可用性)之间。

本平台 9 模块跨进程通信:Console / Trigger / Orchestrator / 4 Worker / 共享 PG / Kafka / Redis / MinIO,涉及任务状态机、outbox 事件、多租户、RBAC、补偿、审批等多写路径。

历史上**没有把 CAP 立场写死**,导致:
- 设计 review 时遇到"分区时怎么办"反复辩论
- 部分 proxy / fallback 各自决策(triggerList 降级 / outbox 写 fail-fast)缺乏统一原则
- 新模块开发不知道默认走哪一边

## 决策

### 1. 核心调度链路 = **CP**(强一致 + 分区容忍,牺牲可用)

**定义"核心链路"**:涉及任何**状态机推进 / 写 DB / 写 outbox / 改资源所有权 / 鉴权决策**的路径。

**必须 C 的理由**(每条都有真实数据正确性 risk):

| 场景 | 不可容忍的不一致 |
|---|---|
| 任务 CLAIM | 两 worker 都 CLAIM 同一 task → 双跑 → 文件 / 数据被处理两遍 |
| 状态机推进 | RUNNING → SUCCESS / FAILED 出现分区两侧不同终态 → 业务方看到非确定结果 |
| outbox 事件 | 业务写 + 事件写跨事务出现中间态 → 消息漏发或重复消费 |
| RBAC / 鉴权 | 分区中给越权 → 安全漏洞 |
| 多租户 `tenant_id` | 分区中跨租户串台 → 隔离破洞 |
| 审批 / 补偿 | 同一审批被双批准 / 补偿双跑 → 业务损失 |
| 配额 / 限流 | 跨节点 quota 不一致 → 突破业务约束 |

**牺牲的 A**(明确允许的可用性下降):

| 故障 | 行为 | 受影响范围 |
|---|---|---|
| PG 主库挂 | 所有写失败 + console 502 | 写 |
| Kafka 不可达 | 派发延迟,outbox 堆 | 派发 |
| Redis 不可达 | 限流降级 / session 回退 cache | session / 限流 |
| Worker 失联 | orchestrator 不再派给它,task 回收 | 单 worker |
| 上游 trigger 服务 down | console trigger 列表降级为空(只读) | 见 §2 例外 |

### 2. 只读 / 观测层 = **AP**(高可用 + 分区容忍,允许 stale)

**定义"只读 / 观测层"**:不影响业务正确性的查询 / 报表 / 监控:

| 路径 | AP 策略 |
|---|---|
| Dashboard / SLA 报表 | last-known cache + stale 时间标 |
| Trigger list(`/api/console/ops/triggers`) | 走 `DownstreamFallback.callOrFallback` → 空 list + WARN(已落地 PR #97/#99)|
| Outbox 查询 | 同上 |
| Cluster diagnostic | 同上 + UI 显示"部分数据降级" |
| `/actuator/health` 子项 | 各组件独立报,不阻塞整体 UP |
| 健康 / 监控 endpoint | 永远响应,空 / stale 优于 503 |

### 3. 不允许的"灰色地带"

- ❌ **业务写路径用 AP 模式**(例:CLAIM 走 eventual consistency)— 直接拒
- ❌ **观测路径用 CP 模式**(例:Dashboard 等所有上游就绪才出页)— 用户体验崩
- ❌ **新加同步外部依赖未声明 fallback 策略**:必须在 `docs/runbook/downstream-degradation.md` 表里登记 fail-fast 或 degrade

## 落地机制

| 机制 | 落地 |
|---|---|
| **写路径 C** | `@Transactional` + outbox 同事务([ADR-002](./ADR-002-transactional-outbox.md)) + CLAIM CAS([ADR-014](./ADR-014-claim-idempotency.md)) + ShedLock 互斥 + PG 主写[读主](./ADR-007-dual-datasource.md) |
| **写路径 P** | spring-retry 瞬时重试 + Idempotency-Key([ADR-011](./ADR-011-idempotency-boundary-alignment.md))+ outbox 异步发([ADR-002](./ADR-002-transactional-outbox.md))|
| **读路径 AP** | `DownstreamFallback.callOrFallback(svc, op, primary, fallback)` 集中模板(PR #97)+ 策略清单(`docs/runbook/downstream-degradation.md`) |
| **降级守护** | 后续:ArchUnit 规则 — 所有 `*ProxyService` public 方法必有 `@CircuitBreaker` 注解或 `DownstreamFallback.call*` 调用 |

## 例外清单(明确登记)

唯一允许的"读路径用 CP"场景:

- **RBAC permission check**(`hasAuthority(...)`)— 必须强一致,session 失效不接受 stale
- **多租户 tenant resolve**(`X-Tenant-Id` → 租户配置)— 不接受 stale 租户配置
- **Idempotency 校验**(Idempotency-Key 查重)— 必须强一致,否则双写

这些"读路径"实际涉及鉴权决策,语义上是 C-critical。

## Spring Boot 4 的隐性 trap

PR #111(`BatchTaskExecutorRegistry` @Autowired)/ #113 / #114(`ComponentScan` miss `spi.task` / `resilience`)证明:Spring 装配错 → bean 缺失 → 整个进程启 hold-back,**比分区更糟**(分区是局部,装配错是全局)。

由 [ArchUnit 守护](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/SpringWiringGuardArchTest.java)(PR #115)防回归。CAP 之外的工程纪律也必须守。

## 何时升级本 ADR

触发条件:

- 业务出现"读路径必须强一致但当前是 AP"的场景 ≥ 3 次 → 重审 §2 表
- 写路径出现可接受 stale 的场景(目前无) → 重审 §1 表
- 引入 Raft / Paxos 等新分布式协议(目前无) → 新 ADR

## 不做的(给边界)

- ❌ 不重写 `DownstreamFallback` 为 Resilience4j(spike #108 进行中,SB4 兼容后单独 ADR)
- ❌ 不引 CRDT / 最终一致 store(业务模型完全不适合)
- ❌ 不做"多活双写"(同 PG 实例双 schema 已 [ADR-007](./ADR-007-dual-datasource.md) 表态)
- ❌ 不为 P3-AZ 多 region 提前设计(等业务需要再开 ADR)
