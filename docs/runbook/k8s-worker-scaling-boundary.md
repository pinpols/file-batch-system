# K8s Worker Scaling Boundary

本文固化 BFS 在 K8s 部署中的边界：K8s 负责副本、滚动、资源配额和 HPA；BFS 负责 worker 注册、drain、lease、任务幂等和容量画像。BFS 不实现自研 K8s 调度器，也不直接改 Deployment replicas。

## 推荐部署形态

| 组件 | K8s 对象 | 扩缩容责任 | BFS 责任 |
|---|---|---|---|
| orchestrator | Deployment 或 StatefulSet | 部署平台保证实例数和滚动升级 | 控制面状态机、lease、outbox |
| trigger | Deployment | 部署平台保证副本和健康探针 | 触发请求、misfire、去重 |
| worker | Deployment | HPA / 手工扩副本 | 注册、心跳、claim、drain、幂等上报 |
| PG / Kafka / Redis / OSS | 外部托管或独立 Stateful 服务 | 基础设施平台 | BFS 只消费连接和健康信号 |

## 必须满足的 worker 配置

- `workerCode` 在同一租户内必须稳定且可区分副本；多副本同名 worker 必须上报 invocationId，避免续租他人任务。
- `workerGroup` 必须和 job/pipeline 定义一致；扩容不会修复 workerGroup 配错。
- Pod `preStop` 必须先调用 worker drain 或让 worker 进入停止接单状态，再退出进程。
- `terminationGracePeriodSeconds` 必须大于一次 lease renew 周期和当前任务安全收尾时间。
- HPA 指标优先用 CPU、内存、Kafka lag、队列 backlog、任务等待年龄，不使用业务金额或账单指标。

## Drain / 滚动升级流程

1. 标记目标 worker drain，停止新任务 claim。
2. 等待已 claim 任务自然终结或到达 drain timeout。
3. 到期仍未终结时，由 BFS 受控 takeover/reclaim，不手改 DB。
4. K8s 终止旧 Pod。
5. 新 Pod 注册并恢复接单。

## 验收

- 滚动升级期间 `CREATED + NO_TASK = 0`。
- Kafka lag 能回落到 0。
- 任务终态只能是明确的 `SUCCESS/FAILED/CANCELLED/TERMINATED`，不能长期卡 `RUNNING`。
- 扩 worker 副本后吞吐提升必须通过 `GET /api/console/capacity-profile?groupBy=WORKER` 或 benchmark 报告验证。
- 降副本后小租户不被大租户饿死，仍受 tenant quota / queue policy 约束。

## 明确不做

- BFS 不调用 K8s API 修改 replicas。
- BFS 不实现 Pod placement、node affinity、HPA 策略。
- BFS 不把 cost profile 扩展为云账单或 FinOps。
- BFS 不用 K8s 状态替代 job/task/partition 状态机。
