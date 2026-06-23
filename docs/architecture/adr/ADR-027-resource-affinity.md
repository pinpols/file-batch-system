# ADR-027 · 资源亲和性 / 地理调度

- **Status**: Accepted（**第 3 阶段 / P2-P3 暂缓**，最高越界风险 — 触发条件不到只保留 workerType + capabilityTags + resourceProfile + region/zone 字段）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-019（域级配额）/ §worker_group / §14.3.2 / [ADR 012/021-027 优先级 + 范围边界](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)

## 范围边界（Scope Discipline）

> **本 ADR 是最高越界风险项。**只做"批量任务到 Worker 的路由策略"，**绝不做"自研 Kubernetes Scheduler / 多集群编排 / 节点拓扑"**。本系统不是 K8s，不重做 K8s scheduler。
>
> **判定提问**：「这个调度策略是在挑 worker 还是在挑机器？」挑 worker → 属本 ADR；挑机器 / 挑容器 / 挑节点 → 不属本 ADR（那是 K8s 的活）。

### 当前阶段保留的轻量字段（不属本 ADR，是基线）

- `worker_group`（已有，不变）；
- `workerType` / `capabilityTags` / `resourceProfile` / `region` / `zone` / `dataAffinityKey` 字段预留即可；
- 简单策略：`workerType` 必匹配 → `capabilityTags` 必满足 → `region/zone` 优先匹配 → 不做复杂打分。
- 单机房 + 同质 worker + 无合规隔离场景：`worker_group` 完全够用，本 ADR 不开工。

### 完整版（触发条件到了再做）

| ✅ 做（本 ADR 完整版） | ❌ 绝不做（系统定位红线） |
|---|---|
| K8s 风格 label + affinity（required / preferred / antiAffinity / weight 排序） | 自研 Kubernetes Scheduler / 多集群调度平台 / 容器资源编排器 |
| Taint + toleration（NO_SCHEDULE / PREFER_NO_SCHEDULE） | PodAffinity / TopologySpreadConstraints / 节点拓扑调度 |
| `WAITING_NO_AFFINITY_MATCH` 临时不可调度 + reconciler 周期重 evaluate | 底层机器资源编排 / 节点生命周期管理 |
| label op：In / NotIn / Exists / DoesNotExist 四种（K8s 一致） | 复杂 multi-objective optimization 调度算法 |
| 数据本地性 / GPU / 合规隔离 / 机房倾斜 / license 绑机 5 类典型场景 | label 表达式（regex / 计算字段） — K8s 也只支持 In/NotIn/Exists/DoesNotExist |

## 背景

当前 worker 调度只到 `worker_group` 字段（简单字符串分组）：

- worker 启动声明 `worker_group=import-pool-A`；
- job 上配 `worker_group_filter=import-pool-A` 限定走该池；
- 没有"标签 + 容忍度"的细粒度匹配。

真实场景：

- **数据本地性**：某 IMPORT job 只能在能直连 NAS A 的 worker 上跑（避免跨 region 拉文件几 GB）；
- **GPU 资源**：ML 推理 job 只能跑 GPU worker；
- **合规隔离**：风控 / 反洗钱 worker 不能跑普通 ETL，反之亦然；
- **机房倾斜**：黄金机房（绿色电力 / 高 SLA）优先跑核心；普通机房跑次要；
- **license 限制**：某商业组件 license 绑机器，job 必须落到指定 worker；
- **网络分段**：某 EXPORT job 调 SaaS API，只有打通防火墙的 worker 能调。

业界 K8s scheduler / Mesos / Nomad 都有 label / taint / toleration / affinity 完整模型。

## 决策

引入 K8s 风格的 label + affinity，与现有 `worker_group` 共存（不破坏）：

### 核心模型

```sql
batch.worker_label
  id              BIGSERIAL PK
  worker_id       BIGINT
  label_key       VARCHAR(64)
  label_value     VARCHAR(128)
  UNIQUE (worker_id, label_key)
  INDEX (label_key, label_value)
```

worker 启动时上报 labels（配置 / 自动检测，例如 GPU 检测）：

```yaml
batch:
  worker:
    labels:
      datacenter: dc-shanghai-1
      hardware: gpu-v100
      license: oracle
      network: prod-fw-zone-A
      compliance: amlrisk
```

`job_definition` 加 affinity 字段：

```sql
ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS affinity_json JSONB;
```

`affinity_json` 形态（K8s 风格简化版）：

```json
{
  "required": [
    {"key": "datacenter", "op": "In", "values": ["dc-shanghai-1"]},
    {"key": "license", "op": "In", "values": ["oracle"]}
  ],
  "preferred": [
    {"weight": 80, "key": "hardware", "op": "In", "values": ["gpu-v100", "gpu-a100"]},
    {"weight": 20, "key": "datacenter", "op": "NotIn", "values": ["dc-test"]}
  ],
  "antiAffinity": {
    "key": "compliance",
    "op": "NotIn",
    "values": ["amlrisk"]
  }
}
```

### Taint / Toleration

worker 加 taint（"我这台机器有特殊性，普通 job 别落"）：

```sql
batch.worker_taint
  worker_id, taint_key, taint_value, effect
  -- effect: NO_SCHEDULE / PREFER_NO_SCHEDULE
```

job 加 toleration（"我能容忍这种 taint"）：

```json
"tolerations": [
  {"key": "compliance", "op": "Equal", "value": "amlrisk", "effect": "NO_SCHEDULE"}
]
```

调度匹配：worker 有 taint 默认拒绝调度，除非 job 显式 tolerate。

### 调度链改造

现有 task dispatch 路径加 affinity 过滤层：

```
PartitionDispatchService.dispatch
    │
    ▼
1. worker_group filter（保留兼容）
    │
    ▼
2. AffinityResolver.filter(workers, job.affinity)
   ├── required 全部满足才候选
   ├── taint vs toleration 检查（不容忍 NO_SCHEDULE 的 worker 移出候选）
   └── preferred + weight 排序
    │
    ▼
3. quota / load 决策（保留现有）
    │
    ▼
4. 选定 worker → 派发
```

候选空集 → task 进 `WAITING_NO_AFFINITY_MATCH` 状态（不等同于 FAILED），reconciler 周期重 evaluate（worker scale 后可能命中）。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 2 张新表（worker_label / worker_taint）+ job_definition 加 1 JSONB 列 + task 加 1 终态 |
| 模块 | worker SDK 加 label / taint 上报；orchestrator 加 `AffinityResolver`；console-api 加 label / taint / affinity CRUD |
| 性能 | 调度路径每次 dispatch 多 1 次 label 过滤；预期 worker 数 ≤ 1000，过滤 < 10ms |
| 兼容 | affinity_json IS NULL → 行为同 worker_group 时代；启用对每个 job 独立 |

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | worker_label / worker_taint schema + worker SDK 上报 | 3 天 |
| 2 | `AffinityResolver` (required / preferred / weight 排序) | 4 天 |
| 3 | toleration 匹配 + WAITING_NO_AFFINITY_MATCH 状态 + reconciler | 3 天 |
| 4 | 调度链接入 + quota 联动顺序确定 | 2 天 |
| 5 | console label / taint / affinity CRUD | 3 天 |
| 6 | E2E（GPU job + 数据本地性 + 合规隔离三场景） | 2 天 |
| 7 | 监控：affinity hit ratio / no-match 时长 metric | 1 天 |

总 ~18 人天。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 多个 worker_group 物理切（per affinity） | 当 affinity 维度 ≥ 3（机房 + 硬件 + 合规）时 group 数量爆炸（笛卡尔），运维不可承受 |
| 让业务 job 自己 SQL filter worker | 跨服务边界，调度逻辑泄漏到业务侧 |
| 直接用 K8s scheduler | 把 batch 平台耦合到 K8s 上，且 K8s scheduler 不感知"批量任务"语义 |

## 不变量

1. `required` 不满足必拒绝（不允许"找不到完美匹配就将就一个"）；
2. taint NO_SCHEDULE 必须显式 tolerate 才落，无 PREFER 回退（PREFER_NO_SCHEDULE 才是"软"）；
3. `affinity_json` 改动需要 PR + audit（高风险变更，影响调度行为）；
4. WAITING_NO_AFFINITY_MATCH 不算 task 失败（不刷 retry / 不刷 SLA），是临时不可调度；
5. worker_label / taint 改动 worker 侧上报后立即对**新调度**生效，不动已运行 task。

## 验收

- 单测：required / preferred / taint / weight 各 case
- IT：删除一个 worker label → 已运行 task 不动，新 task 进 WAITING
- E2E：GPU job 能且仅能落 GPU worker；合规隔离 anti-affinity 真实切割
- 守护：`AffinityCycleTest`（防止 anti-affinity 互拒导致永远调度不出去）

## 实施触发条件

满足任一：
1. **多机房**：worker 部署 ≥ 2 个机房，且业务有数据本地性 / 容灾倾斜诉求；
2. **异构硬件**：worker 池里有 GPU / FPGA / 高内存 / 低内存等不同形态；
3. **合规隔离**：监管 / 合规要求"风控 worker 物理隔离普通 ETL"；
4. **license 绑机**：商业组件 license 限定在某些 worker 上跑（Oracle 客户端 / Db2 client 等）；
5. **worker_group 数量爆炸**：当前 worker_group 数 ≥ 8 且仍增长。

单机房 + 同质 worker + 无合规隔离场景不开工 — `worker_group` 完全够用。

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | affinity 与 quota 顺序 | **affinity 先**：先过滤候选，再过 quota；不可调度 worker 不应该占 quota slot |
| 2 | preferred 权重计算粒度 | 简单求和（命中权重之和最大者胜）；不引入复杂 scheduling theory（如 multi-objective optimization） |
| 3 | label 是否支持表达式（regex） | 不做；K8s scheduler 也只支持 In/NotIn/Exists/DoesNotExist 四种 op，已够 |
| 4 | 跨租户 worker 共享 | 通过 worker_label 维度表达即可（label `tenant=t1` / `tenant=t2`）；不引入新维度 |

### 不会做

- ❌ 不实现完整 K8s scheduler 语义（PodAffinity / TopologySpreadConstraints 暂不需要）
- ❌ 不允许 affinity 跨 ADR-019 业务域借调（隔离维度正交）
- ❌ 不让 affinity 影响重放（ADR-020 replay session）的 worker 选择 — 重放走原 jobDefinition 的 affinity
- ❌ v1 不做 "auto-rebalance" 已运行 task（保守原则；运行中 task 不动，新调度才看新 affinity）
