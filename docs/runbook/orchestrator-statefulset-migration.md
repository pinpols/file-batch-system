# orchestrator 从 Deployment 迁移到 StatefulSet

**适用场景**：首次升级到包含 `feat(k8s): orchestrator 改 StatefulSet` 提交（`162bfb5d` 及之后）
的 Helm chart。**此后的升级不再需要本文档**。

## 为什么要改

老 Deployment 方案要求部署方手工维护两项：
- 固定的 `BATCH_OUTBOX_SHARD_TOTAL=N` 环境变量
- 每个 Pod 单独的 `BATCH_OUTBOX_SHARD_INDEX=0..N-1`

水平扩缩容时必须同步改两者，容易漏。

新方案改用 StatefulSet：
- Pod 名带稳定 ordinal（`batch-orchestrator-0..N-1`），`entrypoint.sh` 自动从 Pod 名末尾
  提取 ordinal 注入 `BATCH_OUTBOX_SHARD_INDEX`
- `BATCH_OUTBOX_SHARD_TOTAL` 由 `orchestrator.replicaCount` 模板化注入
- 扩缩容只需 `helm upgrade --set orchestrator.replicaCount=N`

## 关键约束：Helm 不能原地替换 Deployment → StatefulSet

两种 workload 共用 **同名 Service selector**，Helm 在 `helm upgrade` 时会先尝试更新
Deployment 资源、再创建 StatefulSet，但 K8s API 不允许把 kind 在同名资源上切换，结果：

- 老 Deployment 继续留着跑老镜像（但 Helm 认为已升级）
- 新 StatefulSet 无法创建（name conflict）或创建出来后与老 Deployment 共存，造成 2x Outbox 处理

**必须手工清理老 Deployment 后再 helm upgrade**。

## 升级步骤（按顺序执行）

### 1. 前置检查

```bash
# 确认当前是老 Deployment（期望看到 kind: Deployment）
kubectl -n <ns> get deployment,statefulset -l app.kubernetes.io/component=orchestrator

# 确认 chart 版本已包含本次改动
helm -n <ns> list
helm -n <ns> get manifest <release> | grep -A 2 "kind:.*Orchestrator" | head
```

如果已经是 StatefulSet，**无需执行本文档**，正常 `helm upgrade` 即可。

### 2. 停流量（可选，推荐）

如果 orchestrator 暴露 HTTP 给 trigger/worker 调用，先把入口流量断掉：
- 网关层挂维护模式，或
- 把 orchestrator Service 的 selector 临时改到不存在的标签，让新旧流量都进不来

**目的**：避免迁移窗口期 Outbox 并发写入 / 任务状态竞态。

### 3. 确认 Outbox 已消费干净

```sql
-- 连 platform DB
SELECT publish_status, COUNT(*)
FROM batch.outbox_event
WHERE publish_status IN ('NEW', 'PUBLISHING')
GROUP BY publish_status;
```

等到 `NEW`/`PUBLISHING` 都为 0，再进入下一步；否则正在处理的消息可能被迁移中断
（应用设计上能 recover，但避免人为造浪费）。

### 4. 缩容老 Deployment 到 0

```bash
kubectl -n <ns> scale deployment <release>-batch-platform-orchestrator --replicas=0

# 等所有 orchestrator Pod 退出
kubectl -n <ns> get pods -l app.kubernetes.io/component=orchestrator -w
```

应用侧做了优雅停机（`server.shutdown=graceful` + outbox shard 的单次循环会跑完），
老 Pod 通常 10-30 秒内干净退出。

### 5. 删除老 Deployment 资源

```bash
kubectl -n <ns> delete deployment <release>-batch-platform-orchestrator
# 对应的 Service（ClusterIP）保留，helm upgrade 会直接复用/更新
```

**不要** `helm uninstall`——那会连 Service / ConfigMap / Secret 一起删，破坏其他模块的依赖链。
只删 Deployment 这一个 kind。

### 6. Helm upgrade 安装新 StatefulSet

```bash
helm upgrade <release> path/to/batch-platform \
  -n <ns> \
  --reuse-values \
  --set orchestrator.replicaCount=2
```

Helm 会按 chart 新模板创建：
- `<release>-batch-platform-orchestrator`（**StatefulSet**，2 个 Pod）
- `<release>-batch-platform-orchestrator-headless`（新增 headless Service）
- 原 `<release>-batch-platform-orchestrator`（ClusterIP Service，selector 复用）
- `<release>-batch-platform-orchestrator`（PDB，新增）

### 7. 验证新 StatefulSet

```bash
# 1) Pod 名带 ordinal
kubectl -n <ns> get pods -l app.kubernetes.io/component=orchestrator
# 期望：xxx-orchestrator-0, xxx-orchestrator-1 两个 Pod Running

# 2) SHARD_INDEX 被 entrypoint 正确注入（查 Pod log 开头）
kubectl -n <ns> logs <release>-batch-platform-orchestrator-0 | head -5
# 期望首行包含：
# [entrypoint] StatefulSet ordinal detected: POD_NAME=...-orchestrator-0, BATCH_OUTBOX_SHARD_INDEX=0

kubectl -n <ns> logs <release>-batch-platform-orchestrator-1 | head -5
# 期望 BATCH_OUTBOX_SHARD_INDEX=1

# 3) SHARD_TOTAL 注入正确（应等于 replicaCount）
kubectl -n <ns> exec <release>-batch-platform-orchestrator-0 -- sh -c 'echo $BATCH_OUTBOX_SHARD_TOTAL'
# 期望：2

# 4) 健康端点正常
kubectl -n <ns> port-forward <release>-batch-platform-orchestrator-0 8082:8082
curl -s http://localhost:8082/actuator/health | jq .status
# 期望：UP

# 5) PodDisruptionBudget 生成
kubectl -n <ns> get pdb -l app.kubernetes.io/component=orchestrator
# 期望：<release>-batch-platform-orchestrator, maxUnavailable: 1

# 6) Outbox 双分片都在工作（查应用日志）
kubectl -n <ns> logs <release>-batch-platform-orchestrator-0 | grep -i outbox | head -3
kubectl -n <ns> logs <release>-batch-platform-orchestrator-1 | grep -i outbox | head -3
```

### 8. 恢复流量

解除步骤 2 的入口拦截。

## 回滚方案

如果新 StatefulSet 有问题，快速回滚：

```bash
# 1. 缩容到 0
kubectl -n <ns> scale statefulset <release>-batch-platform-orchestrator --replicas=0

# 2. helm rollback 到上一个 release（会恢复老 Deployment）
helm -n <ns> rollback <release>

# 3. 删除新的 StatefulSet（rollback 不会自动删）
kubectl -n <ns> delete statefulset <release>-batch-platform-orchestrator
kubectl -n <ns> delete service <release>-batch-platform-orchestrator-headless
kubectl -n <ns> delete pdb <release>-batch-platform-orchestrator

# 4. 确认老 Deployment 起来
kubectl -n <ns> rollout status deployment/<release>-batch-platform-orchestrator
```

## FAQ

**Q: 为什么不让 Helm 做 pre-upgrade hook 自动清理老 Deployment？**
A: Helm hook 能跑 `kubectl delete`，但生产环境通常要求手工审阅高风险变更；一次性迁移自动化收益
不抵审计/回滚复杂度。加 hook 后当出问题回滚会更脏（hook 不被 undo）。

**Q: 为什么 orchestrator 必须 StatefulSet，其他模块不用？**
A: 只有 orchestrator 的 Outbox 分片机制需要稳定 ordinal。trigger 用 Quartz 集群抢占、
worker 用 Kafka consumer group 再平衡、console-api 完全无状态——全都不需要稳定身份，
继续走 Deployment 即可（性能/调度更优）。

**Q: 扩容时要怎么做？**
A: `helm upgrade --set orchestrator.replicaCount=N`。chart 会渲染出新的 `replicas=N` 到
StatefulSet，新增 Pod 自动拿到 ordinal N-1；同时 `BATCH_OUTBOX_SHARD_TOTAL=N` 注入
所有 Pod，helm 触发滚动更新让全部 Pod 拿到新的 SHARD_TOTAL。

**Q: 缩容时 Outbox 老分片会丢吗？**
A: 不会。缩容前 `helm upgrade` 重渲染 SHARD_TOTAL 为新值，所有 Pod 重启后按新值做
`id % new_total = shard_index` 路由，原属已下线分片的 outbox 记录会被剩余 Pod 捞走。
应用设计容忍这种分片变化（Outbox 查询条件动态跟随配置）。

## 相关文档

- [基础依赖部署方案](base-services-deployment.md) — 应用层云原生能力评估
- [滚动升级 worker](rolling-upgrade-workers.md) — worker 模块的常规灰度流程
