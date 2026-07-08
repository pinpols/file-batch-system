# 运维 Runbook 索引

部署 / 监控 / 灰度 / 应急 / 巡检 五类 SOP。每份都设计为"事到临头能照着跑"。

> **应急入口**:发生线上故障时,先看 [04 incident-response.md](./incident-response.md) → [05 troubleshooting-decision-tree.md](./troubleshooting-decision-tree.md)。

## 文件清单（编号按"用得最频繁→最少见"排序）

### 一、应急 / 救火（出问题就要打开）

| # | 文件 | 作用 |
|---|---|---|
| 01 | [daily-inspection.md](./daily-inspection.md) | 日常巡检 SOP（每日 / 每周 / 每月 check） |
| 02 | [incident-response.md](./incident-response.md) | 故障响应 runbook（P0/P1 标准动作） |
| 03 | [troubleshooting-decision-tree.md](./troubleshooting-decision-tree.md) | 故障决策树（按症状分支定位） |
| 04 | [compensation-cleanup.md](./compensation-cleanup.md) | Compensation 失败清理（长期停滞的补偿任务怎么搞） |

### 二、部署 / 上线（首次部署或上量）

| # | 文件 | 作用 |
|---|---|---|
| 05 | [docker-deployment.md](./docker-deployment.md) | Docker 部署基线（compose / 单机） |
| 06 | [base-services-deployment.md](./base-services-deployment.md) | Postgres / Kafka / MinIO / Redis 基础依赖部署 |
| 07 | [local-development.md](./local-development.md) | 本地开发环境与联调说明 |
| 08 | [orchestrator-statefulset-migration.md](./orchestrator-statefulset-migration.md) | orchestrator 从 Deployment 迁到 StatefulSet |
| 09 | [rolling-upgrade-workers.md](./rolling-upgrade-workers.md) | Worker 滚动升级（Kafka rebalance 安全姿势） |

### 三、容量 / 弹性（上量评估）

| # | 文件 | 作用 |
|---|---|---|
| 10 | [autoscaling-strategy.md](./autoscaling-strategy.md) | k8s HPA / VPA 弹性伸缩策略 |
| 11 | [ha-elastic-scaling.md](./ha-elastic-scaling.md) | 水平 HA + 弹性缩容兼容性手册（必看部署前 checklist）|
| 12 | [pg-table-partitioning.md](./pg-table-partitioning.md) | PG 表分区运维（job_instance / outbox 等大表） |
| 13 | [read-replica.md](./read-replica.md) | Console-API 读写分离（PG streaming replication） |
| 14 | [minio-lifecycle-policy.md](./minio-lifecycle-policy.md) | MinIO 桶生命周期策略（自动清理） |
| 15 | [backup-and-pitr.md](./backup-and-pitr.md) | **PG 备份 / PITR / 容量护栏**（上线前必做:base+WAL+逻辑导出、恢复演练、磁盘告警） |
| 15a | [dedup-ledger-retention.md](./dedup-ledger-retention.md) | 幂等 dedup ledger 留存治理（outbox/instance 双台账无自动清理,季度归档 SOP + 清理 SQL） |
| 16 | [ha-readiness.md](./ha-readiness.md) | **生产 HA 就绪 Checklist（P0/P1）**——基础件 HA(Kafka/PG Patroni/备份/Redis Sentinel/PgBouncer)逐项 + 应用侧已做对照 |

### 四、灰度 / 切换（特性开关）

| # | 文件 | 作用 |
|---|---|---|
| 15 | [feature-switches.md](./feature-switches.md) | Phase 2 全部能力开关（quota / cache / replica / kafka / quartz）|
| 16 | [wheel-scheduler-rollout.md](./wheel-scheduler-rollout.md) | Quartz → HashedWheelTimer 灰度上线 SOP |
| 17 | [mq-topic-routing-rollout.md](./mq-topic-routing-rollout.md) | MQ topic 分流（PATTERN / FIXED / TENANT_SCOPED）切换 |

### 五、观测 / 安全 / 验证 / CI

| # | 文件 | 作用 |
|---|---|---|
| 18 | [observability-stack.md](./observability-stack.md) | 一站式观测部署 + 排障 SOP（Prometheus / Loki / Tempo / OTel）|
| 19 | [quartz-capacity-baseline.md](./quartz-capacity-baseline.md) | Quartz 容量基线压测（找拐点）|
| 20 | [worker-stage-coverage.md](./worker-stage-coverage.md) | 三类 Worker 全 Stage 真实覆盖端到端验证 |
| 21 | [security-scan.md](./security-scan.md) | 本地安全扫描 SOP（trivy / dependency-check）|
| 22 | [ci.md](./ci.md) | CI 流水线说明（pr-gate / full-ci-gate + 触发时机 + 超时）|
| 23 | [console-login-encryption.md](./console-login-encryption.md) | Console 登录请求体 RSA+AES 加密 + 密钥轮换 SOP |
| 24 | [jvm-tuning-and-profiling.md](./jvm-tuning-and-profiling.md) | JVM 参数(按模块分档)+ profiling 工具链 + 生产 5 类症状 SOP |
| 25 | [credential-matrix.md](./credential-matrix.md) | **凭据矩阵**:各类凭据(片级账密/渠道/密码/内部密钥/JWT/KMS/对象存储/DB)存哪、怎么注入、prod 强校验、上线必配否、谁负责 |

## 角色路径

| 角色 / 场景 | 顺序 |
|---|---|
| 本地起服务 | 07 → 06 → 05 |
| 上 staging | 05 → 06 → 11 → 18 |
| 上 prod | 25（凭据矩阵:逐行核对必配 + prod fail-fast 项）→ 11（部署前 checklist） → 10 → 18 → 02 |
| 救火（长期停滞 / 数据异常） | 02 → 03 → 04 |
| 容量评估 / 上量 | [`../architecture/scalability-assessment.md`](../architecture/scalability-assessment.md) → 11 → 12 → 13 |
| Quartz → Wheel 切换 | 16 → 15 |

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `runbook/`（本目录） | 运维向 / 可执行 SOP |
| [`../architecture/`](../architecture/README.md) | 工程向 / 设计原理 |
| [`../testing/release-gate.md`](../testing/release-gate.md) | release 前的 CI 门禁清单（与上线 SOP 互补）|
| [`../analysis/`](../analysis/README.md) | 问题分析 / 硬化 backlog |
