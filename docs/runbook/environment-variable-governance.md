# 环境变量治理与配置对齐

> 本文说明开发、场景测试、压测、生产四类环境的配置入口、变量职责、对齐检查和变更流程。目标是让运维能快速知道“改哪里、怎么验、哪些不能乱改”。

## 1. 配置源职责

| 配置源 | 作用 | 适用环境 | 不应承载 |
|---|---|---|---|
| `batch-*/src/main/resources/application.yml` | 应用默认值、配置语义、跨环境安全基线 | 所有环境 | 真实密钥、生产地址、本机端口、压测预算 |
| `application-local.yml` | IDE / 本机 JVM 拓扑和开发便利项 | 开发 | 压测容量、生产安全策略 |
| `application-benchmark.yml` | 隔离容量画像参数 | 压测 | 生产默认、业务功能开关、密钥 |
| `.env.example` | 本地 / Compose 常用变量模板 | 开发、场景测试、压测参考 | 全量内部实现参数 |
| `.env.local` / `.env.test` / `.env.prod` | 本机私有覆盖文件 | 本机 | 提交到 Git |
| `deploy/docker/compose/*.yml` | 本地容器网络、端口、卷、服务间 env 透传 | 开发、场景测试、压测 | 生产集群地址和真实密钥 |
| `helm/batch-platform/values.yaml` | Helm 默认值和 K8s 基线 | dev/staging/prod | 本机 localhost、压测临时覆盖 |
| `helm/values-prod.yaml` | 生产 overlay | 生产 | 本地旁路、benchmark profile |
| `docs/runbook/feature-switch-registry.yml` | L0 生产一等开关 required-vars 登记源 | 所有环境 | 容量数字、内部实现参数 |
| `docs/runbook/config-ops-tiering.md` | L0-L3 分层判定 | 所有环境 | 具体密钥值 |

## 2. 环境矩阵

| 环境 | 入口 | Profile | 推荐命令 | 说明 |
|---|---|---|---|---|
| 开发 / IDE | `.env.local` + `application-local.yml` | `local` | `scripts/local/run-tests.sh`、`scripts/local/be-acceptance.sh` | 允许开发便利项，但生产红线仍由 profile 守护兜底 |
| 本地 Compose | `.env.local` + `docker-compose.yml` + `deploy/docker/compose/app.yml` | `local` | `scripts/docker/up-apps.sh` | Java 服务跑在容器内，Compose 负责服务间地址、端口和必需密钥透传 |
| 场景测试 / sim | `.env.local` + `scripts/sim/env-common.sh` | `local` | `scripts/sim/00-reset-runtime.sh`、`scripts/sim/02-start-sim.sh`、`scripts/sim/06-verify.sh` | 验证五类 Worker、幂等、补偿、checkpoint、DR drill 等业务链路 |
| 4 天仿真 | `.env.local` + `scripts/sim-4day/*` | `local` | `scripts/sim-4day/41-run-4days.sh` | 验证跨批量日、日切、归档和残留 |
| 压测 | `.env.local` + `application-benchmark.yml` + benchmark compose overlay | `local,benchmark` | `load-tests/scripts/run-control-plane-worker-benchmark.sh`、`load-tests/scripts/run-process-worker-benchmark.sh` | 必须隔离容量画像，不能把压测参数写回 local/prod 默认值 |
| CI | GitHub Actions 注入 | `test` / workflow 指定 | `scripts/ci/run-full-regression.sh` | CI 不复用本机 `.env.local`，依赖 workflow secrets 和临时服务 |
| Staging | Helm values + staging overlay | `prod` 或 prod-like | `helm lint`、`staging-gate` | 用生产安全策略和较小容量验证发布链路 |
| 生产 | `helm/batch-platform/values.yaml` + `helm/values-prod.yaml` + Secret/ExternalSecret | `prod` | `helm upgrade --install ... -f helm/values-prod.yaml` | 禁止 bypass、必须强密钥、变更走灰度和回滚 SOP |

## 3. 变量分组速查

| 分组 | 代表变量 | 责任和注意点 |
|---|---|---|
| 环境身份 | `SPRING_PROFILES_ACTIVE`、`DEPLOYMENT_ENVIRONMENT`、`COMPOSE_PROJECT_NAME` | 决定安全守护和观测标签；生产必须 `prod` / `production` |
| 时区与编码 | `BATCH_TIMEZONE_DEFAULT_ZONE`、`BATCH_LOCALE`、`TZ`、`LANG`、`LC_ALL` | 全栈唯一源；默认 `Asia/Shanghai` + `C.UTF-8` |
| 数据库 | `BATCH_PLATFORM_DB_*`、`BATCH_BUSINESS_DB_*`、`POSTGRES_*` | 平台库和业务库职责分离；业务库应使用受 RLS 约束的 writer 账号 |
| Redis / ShedLock | `BATCH_REDIS_HOST`、`BATCH_REDIS_PORT`、`BATCH_SHEDLOCK_PROVIDER` | ShedLock provider 是 L0 开关；切换必须全停后统一调整 |
| Kafka | `SPRING_KAFKA_BOOTSTRAP_SERVERS`、`BATCH_KAFKA_BOOTSTRAP_SERVERS`、`BATCH_MQ_ROUTING_MODE`、`BATCH_WORKER_KAFKA_SUBSCRIBE_MODE` | topic 路由和 worker 订阅必须成对灰度，避免 producer/consumer 不匹配 |
| 对象存储 | `BATCH_STORAGE_BACKEND`、`BATCH_S3_*`、`BATCH_STORAGE_FILESYSTEM_*` | 后端切换需要 cutover-id 和对象迁移核对 |
| 内部安全 | `BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET`、`BATCH_SECURITY_BYPASS_MODE`、`BATCH_REQUEST_SIGNING_ENABLED` | 生产强密钥，bypass 必须 false；签名灰度先升 SDK |
| 控制面容量 | `BATCH_TRIGGER_*`、`BATCH_ORCHESTRATOR_*`、`BATCH_RATE_LIMIT_*` | 压测参数写 benchmark profile；生产阈值必须结合连接池、Kafka lag 和 DB 锁等待验证 |
| Worker 能力 | `BATCH_WORKER_*`、`BATCH_WORKER_IMPORT_SCANNER_*`、`BATCH_WORKER_ATOMIC_*` | L0 开关走 registry；细粒度 scanner/skip/drain 参数按 L1-L3 分层处理 |
| 观测与日志 | `MANAGEMENT_OPENTELEMETRY_ENABLED`、`OTEL_*`、`BATCH_LOG_FORMAT`、`BATCH_DOCKER_LOG_*` | 本地默认关闭 OTel；生产接集群 Collector |

## 4. 对齐检查

每次修改配置、Compose、Helm、feature switch 或环境文档后，至少运行：

```bash
bash scripts/python.sh scripts/ci/check-feature-switch-registry.py
bash scripts/python.sh scripts/ci/check-config-defaults-sync.py
bash scripts/python.sh scripts/ci/check-helm-env-sync.py
bash scripts/python.sh scripts/ci/check-config-governance.py
bash scripts/python.sh scripts/ci/check-env-variable-governance.py
helm lint helm/batch-platform
```

检查覆盖：

| 检查 | 防什么 |
|---|---|
| `check-feature-switch-registry.py` | registry 重复、变量名非法、L0 开关登记不完整 |
| `check-config-defaults-sync.py` | Spring yml 默认值与 Compose 默认值漂移 |
| `check-helm-env-sync.py` | Helm 注入了应用不消费的变量，或 L0 开关缺 Helm 入口 |
| `check-config-governance.py` | `@ConfigurationProperties` 未登记，或引入运行时刷新违约 |
| `check-env-variable-governance.py` | 环境变量治理文档、关键入口和常用文件缺失 |
| `helm lint` | Chart 语法、values 结构和模板基本渲染问题 |

## 5. 场景配置建议

### 5.1 日常开发

- 保持 `SPRING_PROFILES_ACTIVE=local`。
- 没有读副本时显式设 `BATCH_CONSOLE_READ_REPLICA_ENABLED=false`，避免无意义探测日志。
- 不把本机容量调参沉淀到 `application-local.yml`；临时变量写 `.env.local`。
- 本地 app 容器必须显式设置 `BATCH_INTERNAL_SECRET`，避免落回默认弱密钥。

### 5.2 场景测试 / sim

- 使用可重复脚本初始化数据，不手工改数据库形成隐式前置条件。
- 运行前清理历史 runtime 数据，防止旧任务污染日志和 DLQ。
- 需要验证对象存储事件到达时开启 `BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED=true`，但仍保留轮询兜底。
- sim 结果只证明业务链路正确，不直接作为容量承诺。

### 5.3 压测

- 必须使用 `local,benchmark`，并记录机器、Docker、JDK、PG/Kafka/Redis/MinIO 参数。
- 压测参数只放 `application-benchmark.yml`、benchmark compose overlay 或压测脚本环境变量。
- 严禁把压测连接池、并发、队列容量写入普通 local 默认值。
- 对比前后性能时，必须保持同一环境签名；机器或 Docker/JDK 变化后重新建立基线。
- 验收至少看 HTTP 错误率、终态率、Kafka lag、Outbox backlog、Hikari 等待、PG 锁等待、worker CPU 和磁盘余量。

### 5.4 生产

- 生产 Helm 必须注入 `BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET`、对象存储凭据和 DB 凭据。
- `BATCH_SECURITY_BYPASS_MODE=false`，禁止在 prod-like profile 下开启。
- `BATCH_QUOTA_REDIS_FAILURE_MODE=FAIL_CLOSED`，不得用 FAIL_OPEN 作为长期生产策略。
- 切换 `storage.backend`、`quota.runtime-store`、`worker.report-outbox` 必须使用 cutover-id。
- 切换 `shedlock.provider` 必须全停统一切，禁止双 provider 并行。
- Kafka topic 分区和副本数投产前规划到位；分区只能增加不能减少。

## 6. 变更流程

1. 先按 `config-ops-tiering.md` 判断 L0/L1/L2/L3。
2. L0：更新 registry、Compose、Helm、feature-switches、`.env.example` 常用模板。
3. L1：更新专项 runbook 或 values 示例，不进入公共能力开关表。
4. L2：写清应急使用条件和恢复默认要求。
5. L3：只保留代码默认和必要注释，不扩散到运维入口。
6. 跑 §4 检查并在 PR 描述里贴结果。

## 7. 常见错误

| 错误 | 后果 | 正确做法 |
|---|---|---|
| 同一个变量同时在 profile、Compose 和 Helm 各自写不同默认 | 本地、CI、生产行为分裂 | 选唯一所有者，其余只透传 |
| 为了压测临时调大 local 默认 | 后续开发环境变慢或掩盖生产容量问题 | 放 benchmark profile，并在报告里记录 |
| 把所有 `${BATCH_*}` 都加入 `.env.example` | 运维入口过载，真正关键开关被淹没 | L0/L1 才进入模板，L2/L3 保持 runbook 或代码默认 |
| 生产用 `.env.prod` 直连真实密钥 | 密钥泄漏、审计困难 | 使用 Secret/ExternalSecret/CI 注入 |
| 只改 Helm 不改 registry | CI 无法识别这是生产一等入口 | 先登记 registry，再补 Helm |
