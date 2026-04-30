# ADR-011: Console / Trigger / Orchestrator 三层幂等责任边界对齐

- **状态**: 已落地(代码已实施,本 ADR 是事后定稿,close hardening-backlog V6-P2-CONSOLE-IDEMPOTENCY)
- **日期**: 2026-04-30
- **决策人**: 后端平台团队
- **关联**: [`docs/analysis/deep-issue-analysis.md` §5.5 / §5.6 / §5.10](../../analysis/deep-issue-analysis.md)
- **解决问题**: 三层(console HTTP / trigger service / db 唯一约束)对"幂等"的责任边界不一致,接口契约与实际行为有偏差

## 背景

`docs/analysis/deep-issue-analysis.md` §5.5 / §5.6 / §5.10 三处指出:幂等语义在 console / trigger / db 三层的实现不统一,容易出现:

- **console 拦截器假冲突**:不同 endpoint / 不同租户共用 `Idempotency-Key` 互相阻塞;失败请求占着 key 24h 让调用方无法重试。
- **trigger 接口契约虚假**:`/api/triggers/catch-up/approve` `@RequiredHeader("Idempotency-Key")` 但 service 不消费,header 是装饰物。
- **DB 约束与代码注释漂移**:V37 已删 `uk_trigger_request_tenant_dedup`,但代码注释仍说"应当为 (tenant_id, dedup_key) 加唯一约束",阅读者会按注释方向去"修复"已经故意去掉的约束。

三处合在一起,加之 `uk_job_instance_tenant_dedup` 才是真正的事实源,使得"哪一层是最终幂等"在团队内没有统一答案。

## 决策

**三层责任边界明确分工,各自只做自己擅长的那一段:**

### Layer 1 — Console HTTP 拦截器(`ConsoleIdempotencyInterceptor`)

**职责**:在控制层对**重复 POST 请求**做去重,只拦截"在短时间窗口内重复提交"。**不**承担最终业务幂等。

**实现要点**:

- Key = `console:idempotency:{tenant}:{method}:{uri}:{idempotencyKey}` — 绑定 tenant + URI + method,杜绝跨接口/跨租户假冲突。
- 两阶段占坑:
  - `preHandle` 写 `PENDING`(30s TTL),并发同 key 直接 409 + `Retry-After: 30`。
  - `afterCompletion` 根据 HTTP 响应:2xx → 升级 `DONE`(24h TTL,长期防重复);非 2xx → DELETE,允许调用方安全重试。
- 失败时**显式释放**占位,不再让"业务异常 / 5xx"把 key 锁 24h。
- Redis 不可达:fail-closed 503(与限流的 fail-open 形成对照,前者保可用,后者保安全)。
- 仅 POST 生效;`@Idempotent` 标注的 endpoint 缺 header 直接 400。

**SLA**:同 key 在 24h 内重复提交 → 第二次返回 409 `CONFLICT_DONE`(已处理)或 `CONFLICT_PENDING`(处理中)。

### Layer 2 — Trigger Service(`DefaultTriggerService`)

**职责**:在触发服务层做**业务级尽力去重**(查 `trigger_request` 是否已存在该 dedupKey 的 `LAUNCHED` 记录,有就直接返回该记录的 `traceId`)。**不**承担"事实事件唯一性"——那由 orchestrator 兜底。

**实现要点**:

- `launch()`:把 `Idempotency-Key` 透传作为 `dedupKey`,`persistAndForward()` 内部 select-then-insert 在 `REQUIRES_NEW` 小事务中完成(缩小竞态窗口)。
- `approvePendingCatchUp()`:用 `idempotencyKey` 查 `trigger_request` 是否已 `LAUNCHED`,是则短路返回(已处理过的审批不重复发 trigger,见 `DefaultTriggerService.java:134-142`)。
- **不强制** `(tenant_id, dedup_key)` 唯一约束 — 同一业务 key 在 trigger_request 表可能产生多条 request_id 不同的记录(调用方重试时 requestId 各不相同但 dedupKey 相同),这是正常 retry 路径而非异常。
- 转发到 orchestrator 前,trigger 的去重是 best-effort:即使两条 trigger_request 同 dedupKey 各自走通,orchestrator 端仍会被 `uk_job_instance_tenant_dedup` 拦掉一条。

**SLA**:同 dedupKey 大概率短路返回(命中 best-effort dedup),小概率两条 trigger_request 都送到 orchestrator(此时 orchestrator 兜底)。

### Layer 3 — Orchestrator DB 唯一约束(`uk_job_instance_tenant_dedup`)

**职责**:**事实源唯一性**——保证同 `(tenant_id, dedup_key)` 在 `job_instance` 表只会落地一条。

**实现要点**:

- DB 层 `UNIQUE(tenant_id, dedup_key)` 约束,INSERT 冲突 → `DuplicateKeyException`。
- `LaunchApplicationService.launch()` 捕获 dup 异常,select existing 行返回(同 traceId)。
- 这是 trigger / console 都依赖的"最后一道墙",不可绕过。

**SLA**:严格 1 条 / `(tenant_id, dedup_key)`。任何路径(trigger / 直接 launch / kafka consumer / outbox replay / catch-up approve)走到这里都会被收敛到这唯一一条。

## 三层关系图

```
HTTP POST + Idempotency-Key
        ↓
┌──────────────────────────────────────────┐
│ Layer 1: ConsoleIdempotencyInterceptor   │  防"30s-24h 内重复 POST",scoped to (tenant+uri+method+key)
│   ✓ 失败释放,允许安全重试                  │
│   ✓ Redis fail-closed (503)              │
└──────────────────────────────────────────┘
        ↓ (通过/REJECT 409)
┌──────────────────────────────────────────┐
│ Layer 2: DefaultTriggerService           │  Best-effort 业务级去重(查 trigger_request)
│   ✓ approvePendingCatchUp 短路 LAUNCHED  │
│   ✓ trigger_request 无唯一约束(V37)      │  允许同 dedupKey 多条 request_id
└──────────────────────────────────────────┘
        ↓ (HTTP / Kafka envelope)
┌──────────────────────────────────────────┐
│ Layer 3: uk_job_instance_tenant_dedup    │  事实源唯一(DB constraint)
│   ✓ DuplicateKeyException → select       │  绝对保证 1 条 / (tenant, dedup_key)
└──────────────────────────────────────────┘
```

## 不变量

1. **Layer 1 的 24h DONE TTL** ≪ **Layer 3 的永久唯一约束** — Layer 1 只是"短期防重复 POST",过期后会让请求穿透到 Layer 2/3,最终事实唯一性靠 Layer 3。
2. **Layer 2 是 best-effort,不是 fail-closed** — 单条查不到不代表绝对没人在做,允许两条同时穿透,Layer 3 兜底。
3. **Layer 3 不可被绕过** — trigger / kafka / direct API / outbox replay 任何路径写入 `job_instance` 都必须命中本约束。

## 后果

### 正面

- **接口契约真实**:`Idempotency-Key` header 在 console / trigger 真正生效,不再是装饰物。
- **失败可重试**:Layer 1 失败释放占位,5xx / 业务异常都不会锁 key,客户端可立即重试。
- **跨租户/跨接口隔离**:Layer 1 key 绑定 (tenant+uri+method),不同 endpoint 互不冲突。
- **设计文档对齐代码**:`DefaultTriggerService` 类 Javadoc 明示"trigger 层只做尽力去重,最终去重由 orchestrator 侧 uk_job_instance_tenant_dedup 保证(V37 已删 trigger 层约束)";V37 SQL 注释也已说明同样意图,无歧义。

### 负面 / 接受的取舍

- **Layer 2 的 best-effort 允许极小概率的"两条 trigger_request 都送到 orchestrator"**:接受,理由是 Layer 3 兜底成本低(DB UNIQUE 约束 + select),反而比 trigger 层加锁更稳。
- **Layer 1 PENDING 30s TTL 内的并发请求会被 409**:有意为之,避免突发并发把同 key 双提交到 Layer 2。
- **Layer 1 fail-closed**:Redis 不可用时 console POST 全部 503,运维需把 Redis 列入主链路 SLO 一档。

## 实施验证

代码层面已落地(commit ref by file:line):

| 层 | 文件 | 关键实现 |
|---|---|---|
| Layer 1 | `batch-console-api/.../ConsoleIdempotencyInterceptor.java` | 全文重写;两阶段占坑 + 4 路 key 绑定 + 失败释放 |
| Layer 2 | `batch-trigger/.../DefaultTriggerService.java:134-142` | `approvePendingCatchUp` 短路 LAUNCHED |
| Layer 2 | `batch-trigger/.../DefaultTriggerService.java:60-71` | 类 Javadoc 说明"trigger 层只做尽力去重" |
| Layer 3 | `db/migration/V37__fix_trigger_request_dedup_constraint.sql` | 删 trigger 层约束 + 注释明示 orchestrator 兜底 |
| Layer 3 | `db/migration/V*` `uk_job_instance_tenant_dedup` | 事实源唯一约束(更早 migration) |

## 不做(明示)

- **不**统一三层为同一种"幂等"语义 — 每层都有清晰边界,统一反而模糊职责。
- **不**给 trigger_request 加回唯一约束 — 维持 V37 的决定,trigger 层接受 best-effort。
- **不**改 Layer 1 的 fail-closed 行为 — Redis 是 console 主依赖,不可在不可用时静默放行重复提交。

## 参考

- [deep-issue-analysis.md §5.5](../../analysis/deep-issue-analysis.md) — Console 幂等拦截器设计不完整(已闭环)
- [deep-issue-analysis.md §5.6](../../analysis/deep-issue-analysis.md) — Trigger 补跑审批接口幂等头未真正使用(已闭环)
- [deep-issue-analysis.md §5.10](../../analysis/deep-issue-analysis.md) — Trigger 去重策略设计漂移(已闭环)
- [hardening-backlog.md V6-P2-CONSOLE-IDEMPOTENCY](../../analysis/hardening-backlog.md)
