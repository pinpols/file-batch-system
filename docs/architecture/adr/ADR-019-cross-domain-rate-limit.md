# ADR-019 · 跨业务域限流（business_domain 主模型）

- **Status**: Proposed
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: §14.3.2（设计层缺口）/ `docs/runbook/quota-runbook.md` / `docs/runbook/rate-limit-runbook.md` / `docs/architecture/scalability-assessment.md`

## 背景

现有限流 / 配额维度只到 `(tenant_id, queue_code, job_code)`：

- `RedisQuotaRuntimeStateService` / `DatabaseQuotaRuntimeStateService` 都按 (tenant, queue, job) 计数；
- `SlidingWindowRateLimiter` 也是租户 / job 粒度；
- `worker_group` 是物理隔离手段（独占 worker 池），但配额 / 限流不感知 worker_group；
- `fairShareGroup` 字段存在，但当前只作为 metric tag，没有实际策略生效。

**痛点**：

- 一租户内多业务域同存（交易 + 风控 + 合规）时，风控大批跑会挤占交易端配额，没有领域级隔离阀；
- 跨业务的 quota 借调（风控低峰借给交易）现在做不到；
- 业务域级限流配置没有统一入口，每条策略要重复配 (tenant, queue) → 配置爆炸；
- 平台监控视角缺少"业务域"维度的配额视图。

**当前兜底**：

- "一域一租户"部署模式 — 完全可行，但跨域报表 / 跨域审计要双倍 SSO / 数据汇聚成本；
- 同租户多 worker_group 物理切 — 解决 worker 争抢，但配额逻辑（DB 写入 / 限流计数）仍混在一起。

## 决策（提案）

引入 `business_domain` 一等模型作为可选的额外配额维度：

- 默认不启用，业务沿用现有 (tenant, queue, jobCode) 配额；
- 启用后，新增 `(domain_code)` 维度并入限流 / 配额决策链；
- 父子域支持继承 + 借调（off-peak sharing）。

### 核心模型

```
batch.business_domain
  id                BIGSERIAL PK
  tenant_id         VARCHAR(64)  NOT NULL
  domain_code       VARCHAR(64)  NOT NULL
  domain_name       VARCHAR(256) NOT NULL
  parent_domain_code VARCHAR(64)            -- 可选，构成继承链
  description       VARCHAR(1024)
  enabled           BOOLEAN NOT NULL DEFAULT true
  sharing_policy    VARCHAR(32) NOT NULL DEFAULT 'STRICT'
                                            -- STRICT / BORROW_FROM_PARENT / BORROW_FROM_SIBLINGS
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
  UNIQUE (tenant_id, domain_code)
```

```
batch.domain_quota_policy            -- 域级配额阀值
  tenant_id          VARCHAR(64)
  domain_code        VARCHAR(64)
  max_active_jobs    INTEGER         -- 同时跑的 job_instance 上限
  max_pending_jobs   INTEGER         -- WAITING + READY 上限
  rate_limit_per_min INTEGER         -- 每分钟启动上限
  effective_at       TIMESTAMPTZ     -- 策略生效起点
  created_at         TIMESTAMPTZ
  PRIMARY KEY (tenant_id, domain_code)
```

`job_definition` 加列：

```
ALTER TABLE batch.job_definition
    ADD COLUMN domain_code VARCHAR(64);     -- 可选，NULL = 不参与域级配额
```

### 决策链

quota 决策按"最严格者优先" pipeline：

```
launch request
  │
  ▼
TenantQuota.check     ──── 不通过 → REJECT/QUEUE
  │
  ▼
QueueQuota.check      ──── 不通过 → REJECT/QUEUE
  │
  ▼
DomainQuota.check     ──── 不通过 → REJECT/QUEUE        ← 新增
  │
  ▼
JobQuota.check        ──── 不通过 → REJECT/QUEUE
  │
  ▼
ResourceQuota.check   ──── 通过 → DISPATCH
```

`DomainQuota.check` 命中条件：

1. `job_definition.domain_code` 非空 → 命中；NULL → 跳过本环节；
2. 自身 active_jobs / pending_jobs / rate_limit 用量 < 阈值 → 通过；
3. 用量 ≥ 阈值且 `sharing_policy != STRICT` → 走借调逻辑（见下）；
4. 仍超限 → REJECT 或 QUEUE（按 launch 配置）。

### 借调（sharing_policy）

- **STRICT**：到阀值即拒绝，不借；
- **BORROW_FROM_PARENT**：自身满载时，向父域请求借调一份额度；父域有空余则放行（计数同时计在父子域）；
- **BORROW_FROM_SIBLINGS**：自身满载时，遍历同父的兄弟域，找空闲额度借；按 `domain_code` 字典序确定性扫描，避免抖动。

借调标记落 `job_instance.borrowed_from_domain` 列，便于审计 + retention 时归还配额。

### Redis / DB 双写

复用现有 quota 双轨（`RedisQuotaRuntimeStateService` 主路径 / `DatabaseQuotaRuntimeStateService` 兜底）：

- Redis Hash key：`batch:domain-quota:{tenantId}:{domainCode}` → fields { active, pending, rate_window_start, rate_count }
- DB 表 `domain_quota_runtime_state`（snapshot + recovery 用，结构对齐 `quota_runtime_state`）

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 3 张新表：`business_domain` / `domain_quota_policy` / `domain_quota_runtime_state`；`job_definition` 加 1 列 |
| 模块 | orchestrator quota chain 加 1 环；console 加域 CRUD + 配额配置；worker / trigger 不变 |
| 配置 | `batch.quota.domain.enabled=true/false`（全局开关，默认 false） |
| 兼容性 | 不启用时所有路径跳过 domain 检查，行为不变；启用后 NULL `domain_code` 的 job_definition 仍按原配额跑 |
| 性能 | quota chain 增加 1 个 Redis lookup；命中预期 < 1ms |
| 监控 | 新增 metric tag `domain` 维度；`batch.quota.domain.{active,pending,rate}` 系列 gauge |

## 实施分阶段

| Stage | 范围 | 估算 | 守护 |
|---|---|---|---|
| 1 | schema（business_domain + domain_quota_policy + domain_quota_runtime_state + archive 镜像）| 2 天 | flyway + archive 对齐 |
| 2 | console-api 域 CRUD + 权限点 `domain.read/write` | 2 天 | controller test |
| 3 | `DomainQuotaService`（Redis + DB 双轨）+ 决策链注入 | 3 天 | 单测 + IT |
| 4 | sharing_policy（STRICT / BORROW_FROM_PARENT / BORROW_FROM_SIBLINGS）+ borrow audit | 3 天 | 借调 IT + 死锁分析 |
| 5 | metric / gauge / Grafana 看板 | 1 天 | 观测验收 |
| 6 | 全局开关 + grace 期默认关 | 0.5 天 | 配置守护 |

总：~11-12 人天。

## 替代方案（被拒绝）

| 方案 | 拒绝原因 |
|---|---|
| 用 `worker_group` 物理隔离顶替 | 只解决 worker 争抢，DB 写入 / 限流计数仍混；扩域要新建 worker pool，运维成本高 |
| 在 `tenant_id` 命名里编码域（如 `t1__trading` / `t1__risk`） | 跨域报表 / 审计要把多个 tenantId 当一个看；外键 / 软隔离全乱套 |
| `fairShareGroup` 字段就地扩展 | fairShareGroup 是 metric tag，没建表 / 没策略；硬扩成完整模型不如新增表清晰 |

## 不变量

1. `business_domain.parent_domain_code` 不允许形成环 —— 注册时 cycle check；
2. 借调成功的 instance 必须在终态时把借出的额度归还；finalize 路径写 `borrowed_from_domain` 审计；
3. 关闭某域（`enabled=false`）后，新启动一律 REJECT；已运行的 instance 不影响（grace 完成）；
4. `domain_code` 永不重命名（重命名会破坏审计追踪）；废弃用 `enabled=false` 替代；
5. 全局开关 `batch.quota.domain.enabled=false` 时，本 ADR 所有逻辑短路（性能保底）。

## 验收标准

- 单测：`DomainQuotaServiceTest`（STRICT / BORROW × 命中 / 失败 / 父域 / 兄弟域）
- IT：父子域借调真实场景；并发借调防双借；
- 性能：domain check P99 < 5ms（Redis 主路径）；DB fallback < 30ms；
- 守护：`DomainHierarchyValidator` 启动期 cycle / orphan check fail-fast。

## 开放问题

1. **借调的会计模型**：借出域是否需要"还时计入历史"以做配额回放？倾向 audit log 记录，配额表只存当前；
2. **跨租户域**：同一业务域跨多租户共享额度（例如 SaaS 场景）—— 不在本 ADR 范围，保留扩展点 `cross_tenant_pool_id` 列；
3. **优先级穿透**：高优先级 job 是否可以无视域限流？倾向新增 `priority_breakthrough_threshold`（如 priority ≥ 9 时绕过 SOFT 限流），细节后续 ADR；
4. **观测 vs 强制**：阶段 1 是否先做"观测模式"（计数但不阻塞）？倾向开关三态：`OFF / OBSERVE / ENFORCE`，默认 OBSERVE，运维确认无误再切 ENFORCE；
5. **必要性触发条件**：当前 backlog 没有具体客户投诉。需要先收集"哪些客户多域共存"的实例化案例，触发实施 — 否则按本 ADR 优先级排在 4 个里**最低**。
