# 弹性伸缩策略

本文讲清楚：6 个模块各自用什么扩缩机制、为什么，以及当前的设计是否合理。

## 一张总表

| 模块 | 扩缩机制 | 触发信号 | 默认 | 说明 |
|---|---|---|---|---|
| **console-api** | CPU HPA | CPU 使用率 > 70% | `enabled: false` | BFF 无状态，CPU 随流量线性涨；标准做法 |
| **worker-import** | **KEDA** by Kafka lag | consumer lag > 20 | `enabled: false` | KEDA 直接读消费滞后，比 CPU 准 |
| **worker-export** | **KEDA** by Kafka lag | consumer lag > 20 | `enabled: false` | 同上 |
| **worker-dispatch** | **KEDA** by Kafka lag | consumer lag > 10 | `enabled: false` | 单 dispatch 较重，阈值更小 |
| **orchestrator** | **静态分片（默认）/ DYNAMIC + CPU HPA 或 KEDA Postgres backlog** | CPU % 或 Outbox 积压 | 2 副本 | 切到 `sharding.mode=dynamic` 才能安全自动扩，下文详述 |
| **trigger** | **静态 HA 2 副本** | — | 1 副本（生产改 2） | Quartz 集群锁天然决定 |

**关键洞察**：**不是所有模块都该自动扩**。Worker / console 是纯执行/服务层，自动扩合理；orchestrator / trigger 是协调层，自动扩反而有害或无效。

## 为什么 worker 必须 KEDA 不能 CPU

Worker 大部分时间**阻塞等 Kafka 消息**，CPU ≈ 0。如果用 CPU HPA：

```
情形：Kafka 积压 10 万任务
  Worker 空转等消息 → CPU 低 → HPA 不扩容 → 积压继续堆
  → 下游 SLA 爆掉
```

**KEDA by Kafka lag** 直接读 consumer group 的未消费消息数：

```
Kafka lag > 20 → KEDA 扩 Pod → 新 Pod 加入 consumer group → Kafka 自动 rebalance partition → 新 Pod 分到消息 → lag 下降
```

这是**业务感知**的伸缩，不是机器指标的伸缩。

## 为什么 trigger 不扩

Trigger 跑的是 **Quartz JDBC 集群模式**：多个 Pod 的 Quartz scheduler 竞争同一把 DB 锁。

```
时刻 t：定时触发 job X
  Pod 1 / Pod 2 / Pod 3 都尝试抢 QRTZ_LOCKS 表的锁
  → 只有 1 个 Pod 抢到 → 执行 job X（耗时 Y 秒）
  → 另外 2 个 Pod 空转 Y 秒
```

**扩 Pod 不加速 job 执行，只增加 DB 锁竞争**。Quartz 的并发能力在单 Pod 的 `threadPool.threadCount`（默认 10 线程），不在 Pod 数量。

**trigger 扩副本只有 HA 冗余价值**：1 挂 1 接管。2 副本就够了，不需要 HPA。

## 为什么 orchestrator 静态分片

orchestrator 用 `BATCH_OUTBOX_SHARD_INDEX` 静态分片 Outbox：

```
shardTotal=2
  orch-0: 处理 id % 2 = 0 的 outbox 记录
  orch-1: 处理 id % 2 = 1 的 outbox 记录
```

自动扩容会撞上一致性问题：

```
假设 HPA 扩 2 → 3，shardTotal 还是 2：
  orch-2 启动 → 没活干（shardIndex=2 ≥ shardTotal=2）
  或 orch-2 拿了 shardIndex=0 → 和 orch-0 重复处理 → 下游收双份消息
```

要支持真正自动扩需要**动态 rebalance**（Pod 启动时查 StatefulSet.spec.replicas，各 Pod 同步感知新 shardTotal）。**工程量大且扩容期有 race condition 窗口**，当前项目规模不值得。

**现状**：手工 `helm upgrade --set orchestrator.replicaCount=N` 扩容。滚动重启期间所有 Pod 拿新 `SHARD_TOTAL`，对齐后继续处理。

## Option B 已落地（Phase 1+2）：动态 shard 协调

**代码中已实现**（见 `batch-orchestrator/src/main/java/.../infrastructure/sharding/`），通过
开关切换，默认保持 STATIC 模式不影响现有部署。

### 新配置项

```yaml
batch:
  outbox:
    sharding-mode: static      # static（默认） | dynamic
    sharding:
      heartbeat-interval-ms: 5000
      member-ttl-ms: 30000
      member-id: ""            # 空则从 POD_NAME / hostname 自动解析
```

### 工作机制（dynamic 模式）

```
每个 orchestrator Pod：
  Scheduled @ 5s: ZADD batch:orchestrator:members <pod-name> <now>
  每轮 outbox poll 前：
    ZREMRANGEBYSCORE members 0 (now - 30s)   ← 清死成员
    ZRANGE members 0 -1                       ← 取活成员列表
    排序取 index；数量为 shardTotal
```

**rebalance 触发**：任意 Pod 心跳超时（30s 未写入）或新 Pod 加入时，下一轮 poll 所有存活 Pod
同时观察到新列表，自动重算自己的 shardIndex。

### 切换步骤（零宕机灰度）

1. 先 `helm upgrade --set orchestrator.replicaCount=2`（保留现状 2 副本）
2. 灰度开启：`--set batch.outbox.shardingMode=dynamic`
3. 观察 log 确认 "Outbox sharding mode=DYNAMIC"；`kubectl exec ... redis-cli ZRANGE batch:orchestrator:members 0 -1` 看是否 2 个成员
4. 如果正常，保留配置；有问题 `--set batch.outbox.shardingMode=static` 秒级回退

### 切换后能带来的自动化

- 扩容：`kubectl scale sts batch-orchestrator --replicas=4` 后 30s 内新 Pod 心跳注册，所有 Pod 自动重算 shard
- 缩容：Pod 优雅停机 → 心跳停 → 30s 后其他 Pod 感知，自动吸收该 shard 的数据
- **HPA 已接入 Helm 模板**（默认 `enabled: false`，开 dynamic 后打开即可）：
  - CPU HPA：`orchestrator.hpa.enabled=true`（min 2 / max 6 / CPU 70%）
  - KEDA Postgres backlog：`orchestrator.keda.enabled=true`，按 `outbox_event` 中 `NEW+FAILED` 行数触发，阈值默认 200。需先创建 Secret：

    ```bash
    kubectl -n batch-prod create secret generic batch-keda-postgres \
      --from-literal=connection='host=postgresql port=5432 user=batch_user password=<pwd> dbname=batch_business sslmode=disable'
    ```

  - 守护：模板里 `hpa` / `keda` 渲染都带 `sharding.mode=dynamic` 前置判断，static 下不会创建（避免扩容炸分片）

### 保留 STATIC 为默认的理由

- 生产线上已跑的部署是 STATIC 模式，贸然切 DYNAMIC 有 rebalance 窗口期风险
- STATIC 行为确定性强，合规场景更好审计
- 已验证的业务规模下 STATIC 足够

待业务场景（多租户 SaaS / 峰谷 10x+）到来再把 DYNAMIC 作为默认推送。

## orchestrator 3 种可选设计对比

| 维度 | A. 静态分片（当前） | B. Consumer Group 风格 | C. 无分片 + 每事件分布式锁 |
|---|---|---|---|
| **简单性** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **弹性** | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **吞吐** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐（每事件一次锁往返） |
| **代码复杂度** | 极低 | 中（需写协调者或复用 Kafka CG） | 低 |
| **运维复杂度** | 低（helm upgrade）| 中（需协调服务）| 低 |
| **适合场景** | 负载可预测、合规型 | 多租户 SaaS、突发负载 | 低 TPS、简单优先 |

### A. 静态分片（当前方案）
- 扩缩容走 helm upgrade，分钟级响应
- 心智模型简单，审计友好
- 代价：平时需要冗余副本避免峰值打爆

### B. Consumer Group 风格
Pod 启动时向中央协调者（etcd / Redis）注册：「我活着，分配一片给我」。协调者根据活跃 Pod 数动态 rebalance。
- 类似 Kafka consumer group 机制
- 可以直接开 HPA，扩 Pod 后自动分到新 shard
- 代价：要写协调者（或用 Apache ZooKeeper 等第三方），或让 Outbox 消费走 Kafka 再分派（架构变动大）

### C. 无分片 + 每事件分布式锁
每个 outbox 事件处理前用 ShedLock 抢一个短锁（per-event key）。谁抢到谁处理。
- Pod 数任意，无状态
- 代价：每事件 1 次 Redis/DB 锁往返；lock TTL + 网络抖动可能导致重复处理
- 适合 TPS 不高（<1000/s）场景

## 当前设计的合理性评估

| 指标 | 评分 | 说明 |
|---|---|---|
| **console-api CPU HPA** | 10/10 | 标准做法，无争议 |
| **workers KEDA by Kafka lag** | 10/10 | 批处理 worker 的最优解 |
| **trigger 静态 HA** | 10/10 | Quartz 天然决定，无选择 |
| **orchestrator 静态分片** | **7-8/10** | 合理但保守 |

### 合理的理由

1. **业务负载可预测**：金融/企业批处理都有计划（每日 N 次定时批），不像秒杀那种尖峰；预算性扩容够用
2. **规模匹配**：代码架构目标日百万~千万任务，2-4 个 orch Pod 处理上亿 Outbox 没问题，自动伸缩 ROI 低
3. **合规留痕**：`helm upgrade` 有变更记录，对审计友好；自动扩容的"时间点 / 谁拍板"说不清

### 什么时候该升级

若业务演进满足以下任一条件，orchestrator 应该从 A → B：

- **变成多租户 SaaS**：不同租户负载不确定，静态分片迟钝
- **白天/夜间负载差 10x+**：白天 1k TPS，凌晨批处理 50k TPS → 8 小时过供给 5 倍副本，浪费
- **客户能触发突发**：用户上传大文件瞬间灌爆 Outbox

## 应用层面配置速查

启用所有应开的 autoscaling（叠加配置）：

```bash
helm upgrade batch helm/batch-platform/ -n batch-prod \
  --values values-prod.yaml \
  --values helm/batch-platform/examples/values-autoscale.yaml
```

该 example 打开：
- console-api: CPU HPA（min 2, max 6）
- worker-import: KEDA Kafka lag（lagThreshold 20, max 8）
- worker-export: KEDA Kafka lag（lagThreshold 20, max 8）
- worker-dispatch: KEDA Kafka lag（lagThreshold 10, max 6）

orchestrator / trigger 保持 values.yaml 里的 `replicaCount`，不动。

## 前置基础设施

| 组件 | 用途 | 安装方式 |
|---|---|---|
| **metrics-server** | 提供 Pod CPU/内存给 HPA | `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`（Docker Desktop 自签证书需加 `--kubelet-insecure-tls`）|
| **KEDA operator** | 提供 ScaledObject CRD + Kafka Scaler | `helm install keda kedacore/keda --namespace keda --create-namespace` |

无 metrics-server → HPA `TARGETS: cpu: <unknown>`（不工作）
无 KEDA operator → ScaledObject 资源创建失败（CRD 不存在）

## 相关文档

- [基础依赖部署方案](base-services-deployment.md) — 云原生能力评估
- [orchestrator StatefulSet 迁移](orchestrator-statefulset-migration.md) — 首次从 Deployment 换到 StatefulSet 的步骤
- [滚动升级 worker](rolling-upgrade-workers.md) — worker 灰度流程
