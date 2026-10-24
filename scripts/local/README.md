# 本地开发脚本

本地 JVM 模式下的启停、构建和测试脚本。

## 脚本列表

- `manage-logs.py`：本地日志留存与压缩归档；默认只读预览，原文按类别保存 7/30 天，压缩包保存 90 天后须显式清理；保护当前、打开中、latest、`.keep` 和文档字面引用的证据。策略与恢复说明见[本地日志手册](../../docs/runbook/local-log-layout.md#保存压缩归档与到期清理)。

- `start-all.sh`：一键启动本地联调环境（基础依赖 + 8 个 Java 应用模块）
  - 建表后幂等应用业务库 RLS，避免 Worker 因租户策略缺失启动失败
  - 裸 JVM 自动使用宿主机 PostgreSQL / replica / Redis 地址，并映射 `.env.local` 的数据库凭据
  - 任一应用未通过最终健康检查时返回非零退出码
- `stop-all.sh`：分阶段停止本地 Java 进程
- 裸 JVM 的启动、重启、健康检查和停止共用解析后的应用端口：Console 使用 `CONSOLE_API_PORT` / `BATCH_CONSOLE_API_PORT`，其余模块使用对应的脚本端口 / `BATCH_*_PORT`。只给一侧时同步另一侧；两侧冲突或端口非法时在启动/重启前失败。Worker / Console 的服务调用地址映射到应用实际消费的 `BATCH_ORCHESTRATOR_BASE_URL` / `BATCH_TRIGGER_BASE_URL`；Compose 保留自己的容器网络地址。
- `audit-runtime-warnings.sh`：按当前策略审计所选应用日志；退出 0 为通过，1 为 UNKNOWN/规则过期，2 为日志或策略输入不可用，3 为需要处理的告警。缺少日志目录、没有应用日志或读取错误均失败；已有日志且无 WARN 可以通过。手工/debug/screen 日志不充当应用审计输入。
- `start-all.sh`、`restart.sh`、`stop-all.sh` 和 `build-apps.sh` 共用仓库级本地生命周期锁；
  并发调用会串行等待，进程异常退出后可回收失效锁。默认等待上限 600 秒，可用
  `BATCH_LOCAL_LOCK_TIMEOUT_SECONDS` 调整；设为 `0` 表示无限等待。
- `health-check-infra.sh`：基建健康检查(PG primary/replica / Kafka / Redis / MinIO)。
  协议层探测 + env-var 驱动,**任何环境可用**(本机走 `.env` 默认,staging/CI 覆盖
  `PG_PRIMARY_HOST` / `KAFKA_BOOTSTRAP` / `MINIO_URL` 等)。
  - `--quiet`:只 exit code(供 start-all.sh fail-fast 调用)
  - `--no-replica` / `--no-kafka` / `--no-minio`:跳过某项(staging 无 replica 时)
  - `make dev-health` 是它的别名
- `redis-sentinel-ha-drill.sh`：用隔离 Compose project 验证 Valkey Sentinel 自动切主、切换前复制数据、切换后写入及旧 master 重新加入；退出时清理该 project 的临时卷。
- `pg-replica-failover-drill.sh`：复用 `replica` profile，在隔离 project 验证 standby 晋升、晋升后写入及旧 primary 从新主重新克隆为 standby；不会触碰默认本地 PG 容器或卷。
- `minio-distributed-ha-drill.sh`：启动隔离的 4 节点 MinIO EC:2 集群，验证单节点停止期间对象读写和节点恢复后的数据可用；退出时清理专属卷。
- `watchdog.sh`：长时间联调时挂在另一 tab 自动拉起被系统回收的 worker 进程
（macOS 闲置数小时会回收 JVM；docker-compose 模式不需要本脚本，靠 docker
自带 `restart: unless-stopped` 回退）
- `build-apps.sh`：Maven 打包 8 个应用模块（`-Dmaven.test.skip=true`，不编译测试源码）
- `sim-harness.sh all`：按 preflight、停止运行态写入、reset、前置装配、真实数据检查、完整 SIM 顺序执行；`sim` 保持全量 04–30 零跳过。
  - 前置真实数据路由校验创建的 shard-1 使用当前 Compose project 的 SIM 所有权标签；`verify-data` 完成或失败、`all` 任意退出时都会清理该容器及匿名数据卷。未知同名容器拒绝复用；中断后下次启动只清理带当前 project 双标签的残留，保留其它 project 和 Compose 命名卷。
  - 全量 SIM 后若需清理本轮 SFTP/MinIO 入站 fixture，先停止全部 8 个应用，再执行 `bash scripts/local/sim-harness.sh cleanup-inputs`；命令会检查所有应用端口空闲，只删除 ta/tb/tc 的 SIM 命名输入和 Stage 25/26 命名对象，并复核为空。其它输入和对象保留。
  - preflight 和直接 sim 入口在清理、reset、启动或重启之前检查基础设施容器属于所选 `COMPOSE_PROJECT_NAME`，并核对应用/SDK 的 Kafka 地址及容器实际发布端口。自定义 `KAFKA_HOST_PORT` 时须显式设置匹配的 `BATCH_KAFKA_BOOTSTRAP_SERVERS`；仅修改 SDK 的 `KAFKA_HOST_BOOTSTRAP` 不足以隔离应用。
  - `BATCH_SCRIPT_RUNTIME=host|docker` 优先；auto 只识别当前项目的 Console 归属，暂时停止的 Compose 容器也保留其运行方式。探测失败会停止执行。宿主机 SFTP 端口未显式配置时从 `SFTP_HOST_PORT` 派生，显式不匹配值会被拦截。
  - SFTP 探针及 Stage 20 共用 `/tmp/batch/sim-channel-trust/<Compose project>/known_hosts`，只接受合法项目名作为路径分量；不同项目不再覆盖同一个公钥文件。文件需保留至该环境的渠道重试结束，环境销毁时再清理本项目文件。
  - 已有同一环境的前置条件时，用 `sim-selected 23 25` 定向复验。阶段号必须存在且不能重复；结果明确记录选定范围，不能当成完整 SIM 通过。
  - harness 负责场景开始和结束的 Worker 配置切换；23/25 独立执行时自行恢复并等待健康。25 内部故障注入重启仍由场景负责；退出时按租户/模板比较并恢复自有 chunk 配置，不删除其它请求的 checkpoint 进度。
  - SFTP 准备使用新库配置包的 `sftp_user` / `sftp_remote_directory`，两者须为非空字符串，旧别名不自动转换。`ta_sftp_inbound`、`tb_sftp_inbound`、`tc_sftp_score` 三个自有通道须全部存在且类型为 SFTP；准备仅覆盖环境路由与严格主机公钥配置，任何缺项或部分更新整批回滚。失败时修正配置包后重新准备，不手工跳过校验。SQL 契约由 `SimSftpFixtureContractIntegrationTest` 在真实 PostgreSQL 上执行。
- `pg-replica-failover-drill.sh`、`redis-sentinel-ha-drill.sh`、`minio-distributed-ha-drill.sh`：使用唯一 Compose project
  和独立卷执行本地 PG 主备切换/重新加入、Valkey Sentinel 自动切主/旧主重新加入、MinIO 分布式纠删码单节点故障演练；要求本机 Docker context，
  退出时会删除演练 project 和专用卷，失败时先输出容器日志。
- `import-copy-worth-benchmark.sh`：IMPORT LOAD 写入微基准,对比当前 JDBC batch UPSERT 与
  `COPY -> temp table -> merge` 路径,用于判断是否值得实现 COPY。
- `run-tests.sh`：**本地一键测试入口**（推荐）
  - 默认：单元 + 集成（跳过 E2E）
  - `--unit`：仅单元测试，秒级，无容器
  - `--it`：仅集成测试（`*IntegrationTest`）
  - `--e2e`：仅 E2E 测试（`*E2eIT`）
  - `--all`：单元 + 集成 + E2E 全量
  - `-- <mvn args>`：透传 Maven 参数
- `spark-e2e-runtime.py`：为恢复验收中的真实 Spark 场景准备 Apache Spark 4.0.1 与隔离 Python 3.12；完整 E2E 在构建前检查缓存，运行时不联网下载。具体命令见下方。
- `restore-script-execute-bits.sh`：按 Git 已记录的 `100755` 模式一键恢复脚本执行位；详见[脚本执行权限 Runbook](../../docs/runbook/script-execution-permissions.md)
- `docker-path.sh`：工具函数，确保 docker 在 PATH 中（供其他脚本 source）

## 运行前提

- Docker / Docker Desktop、JDK
- `start-all.sh` / `stop-all.sh` 默认使用 `.env.local`
- `start-all.sh` / `restart.sh` 启动 fat jar 前 source `COMPOSE_ENV_FILE`，并默认导出：`BATCH_TIMEZONE_DEFAULT_ZONE`（时区单一配置源）、`TZ`（同值派生）、`BATCH_LOCALE` / `LANG` / `LC_ALL`（locale 单一配置源）
- `start-all.sh` 默认只启动不打包；如需构建：先 `build-apps.sh` 或 `BUILD=1 start-all.sh`
- 切换分支或删除未发布迁移/其它资源后，先对受影响模块执行 `clean`，再以 `-am` 运行相应构建/测试。Maven 增量资源复制不会删除旧的 `target` 文件；全新数据库验收须核对生成资源和运行包的迁移文件名及内容与源码一致，不能只看最终版本号。例如 E2E 模块先执行 `./mvnw -pl batch-e2e-tests clean`，再执行原 `-pl batch-e2e-tests -am` 验证命令。
- `sim-harness.sh` 会重启本地 Worker 来设置场景模式，应在持续保留的本地终端会话中执行。自动化执行器若会在命令结束时回收整个进程组，可能连带停止脚本启动的后台 JVM；场景通过后仍须独立检查八服务持续健康、PID 和运行包，不能只依赖脚本退出码。

## 常见顺序

1. `build-apps.sh`（首次或代码变更时）
2. `start-all.sh`
3. `scripts/data/init-kafka-topics.sh`、`scripts/data/init-minio.sh`（通常 Docker Compose 自动完成）
4. `health-check-infra.sh`(或 `make dev-health`)— 起 BE 前 / 联调遇怪问题先看基建
5. `run-tests.sh`（日常开发）
6. `run-tests.sh --all`（提交前）
7. `scripts/ops/inspect-all.sh`（巡检）

## 真实 Spark E2E 运行时

`WorkerReportLostAfterBusinessCommitE2eIT` 的两个 Spark 场景使用真实 `spark-submit`，需要先准备一次运行时。准备入口要求可执行的 Python 3.12，默认从 PATH 查找 `python3.12`，也可用 `BATCH_E2E_SPARK_PYTHON` 指定。它创建不继承全局包的独享 venv，使用发行包自带的 PySpark/Py4J zip，不安装全局 Python 包。

```bash
# 显式联网准备：下载固定 Apache Spark 4.0.1 archive，核对固定官方 SHA-512 后解包。
python3 scripts/local/spark-e2e-runtime.py --prepare

# 已有官方 archive 时离线准备；参数可以包含空格。
python3 scripts/local/spark-e2e-runtime.py --prepare --archive /path/to/pyspark-4.0.1.tar.gz

# 只检查缓存文件摘要与 Python/PySpark/Py4J 版本，不启动 JVM 或容器。
python3 scripts/local/spark-e2e-runtime.py --check

# 定向运行；入口自动通过 argv 传入 r25.spark.home / r25.spark.python。
python3 scripts/local/spark-e2e-runtime.py -- ./mvnw -o -ntp \
  -pl batch-e2e-tests -am \
  '-Dtest=WorkerReportLostAfterBusinessCommitE2eIT#shouldStopAtomicSparkReplay_whenWorkerJvmCrashesDuringActiveSparkAction' \
  -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 test
```

默认缓存是仓库忽略目录 `tmp/cache/spark-e2e`，可用 `BATCH_E2E_SPARK_CACHE` 指定其它目录；缓存保存已解包文件的摘要，执行前逐一核对。准备和执行应使用同一缓存配置。损坏缓存不会自动覆盖，应在新缓存目录重新准备。准备被中断而留下 `prepare.lock` 时，先确认原准备进程已退出，再移除该精确锁目录。

`run-tests.sh --e2e`、`--all` 已接入检查与参数传递；缺失运行时会在构建和环境清理前失败并给出准备命令。用明确的 `-Dtest=OtherE2eIT#method` 选择不涉及 Spark 的场景时不要求该缓存；通配符选择保守执行前置检查。直接执行 `./mvnw` 不会准备运行时，Spark 定向命令请使用上述入口。

当前 Full CI / Staging 的 shard 尚未列入此恢复 E2E 类，本次没有改动其测试范围，不能据本地通过宣称 CI 已覆盖。将来接入时，应在该 job 显式准备运行时，并以同一 `spark-e2e-runtime.py -- <Maven 命令>` 入口执行；不能依靠开发者工作树的路径或跳过 Spark 用例。

## 相关目录

- [scripts/ops/](../ops/)：巡检与自愈脚本
- [scripts/data/](../data/)：数据初始化与加载
- [scripts/docker/](../docker/)：Docker 容器操作

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [analyze-replay.sh](analyze-replay.sh) | forensic replay 5 维度 diff harness。 |
| [append-tenant-coverage.py](append-tenant-coverage.py) | 按 "租户分工" 策略向 ta / tb / tc-tenant-config-package-test.xlsx 追加。 |
| [apply-pending-flyway-migrations.sh](apply-pending-flyway-migrations.sh) | Dev mode 工具:console-api 启动后,若 orchestrator/trigger 未起(本地常态),。 |
| [audit-runtime-warnings.sh](audit-runtime-warnings.sh) | 审计当前应用日志中的 WARN：预期本地告警需登记 owner/到期日，。 |
| [backend-reliability-tests.sh](backend-reliability-tests.sh) | 本地与每日 CI 共用的隔离可靠性回归套件。 |
| [batch-consume-load.sh](batch-consume-load.sh) | ADR-046 Phase 2 切片 2.3d —— worker 消费端攒批的「全栈负载验收」。 |
| [be-acceptance.sh](be-acceptance.sh) | BE 全链路验收入口；流程说明见 docs/runbook/be-acceptance.md。 |
| [build-apps.sh](build-apps.sh) | 构建本地运行所需的应用可执行 JAR。 |
| [clean-stale-test-env.sh](clean-stale-test-env.sh) | 清理上一轮 sim / 测试遗留的容器(幂等,随时可跑)。 |
| [cleanup-disk.sh](cleanup-disk.sh) | 本地开发磁盘清理。默认只预览；数据卷、运行日志和构建产物均需显式启用。 |
| [docker-path.sh](docker-path.sh) | 供 start-all.sh / stop-all.sh source:在 PATH 中未找到 docker 时,补齐常见安装路径。 |
| [gen-default-tenant-excel.py](gen-default-tenant-excel.py) | 依据 multi-tenant-seed.sql 里 default-tenant 的 "v4 硬化批次" 新增项。 |
| [health-check-infra.sh](health-check-infra.sh) | 基建健康检查:PG primary/replica / Kafka / Redis / MinIO。 |
| [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) | 判断 IMPORT LOAD 是否值得上 PG COPY 的本地微基准。 |
| [manage-logs.py](manage-logs.py) | 本地日志留存：默认预览，压缩校验后归档，显式清理过期归档包。 |
| [maven-env.sh](maven-env.sh) | 本地脚本共用的 Maven 命令解析逻辑。 |
| [minio-distributed-ha-drill.sh](minio-distributed-ha-drill.sh) | 在独立本地 Compose 项目中演练 MinIO 分布式故障恢复。 |
| [mock-partner.py](mock-partner.py) | 外部伙伴 HTTP 测试服务，用于本地联调 API / API_PUSH dispatch 渠道。 |
| [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) | 在独立本地 Compose 项目中演练 PostgreSQL 主从故障切换。 |
| [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) | 针对 import COPY/UPSERT 跑一个小而可重复的 PG 写参数矩阵。 |
| [pre-commit-checks.sh](pre-commit-checks.sh) | 提交前轻量门禁：只对暂存区命中的文件域执行对应检查。 |
| [pre-push-sdk-checks.sh](pre-push-sdk-checks.sh) | SDK 路线图 PR 推送前自查 — 拦截高频被 CI 拦截的问题:。 |
| [provision-biz-shard.sh](provision-biz-shard.sh) | 幂等「加一片 biz 分片」:起 PG → 建库建表 → rls-phase-a(角色+授权+RLS)→ 只读排故角色 → 写 secrets。 |
| [redis-sentinel-ha-drill.sh](redis-sentinel-ha-drill.sh) | 在独立本地 Compose 项目中演练 Valkey Sentinel 故障切换。 |
| [repair-local-flyway-history.sh](repair-local-flyway-history.sh) | 本地开发库 Flyway history 修复工具。 |
| [replay-forensic-bundle.sh](replay-forensic-bundle.sh) | 拉 forensic 证据包 → 本地 sim 回放 → SQL 比对。 |
| [restart.sh](restart.sh) | 按模块重启当前工作树的本地 Java 服务。 |
| [restore-script-execute-bits.sh](restore-script-execute-bits.sh) | 恢复登记脚本在 Git 索引中的可执行权限。 |
| [run-tests.sh](run-tests.sh) | 本地统一测试入口。 |
| [rustfs-poc.sh](rustfs-poc.sh) | 验证 RustFS 的 S3 兼容能力与本地接入。 |
| [s3-compatible-minio-migration.sh](s3-compatible-minio-migration.sh) | 演练 MinIO 与目标 S3 兼容存储之间的数据迁移。 |
| [sdk-control-e2e.sh](sdk-control-e2e.sh) | 在真实服务链路中对自托管 SDK Worker 注入暂停、恢复、取消和排空控制。 |
| [sdk-control-fault-proxy.py](sdk-control-fault-proxy.py) | 提供仅监听本机的 SDK 控制指令故障代理。 |
| [sdk-e2e-local.sh](sdk-e2e-local.sh) | BYO SDK 样例 worker × 真 orchestrator 本地全链路验证。 |
| [sdk-handler-tests.sh](sdk-handler-tests.sh) | 5 类 worker handler(batteries)单测 × Java + Python SDK。 |
| [seaweedfs-poc.sh](seaweedfs-poc.sh) | 验证 SeaweedFS 的 S3 兼容能力与本地接入。 |
| [seed-test-role-users.sh](seed-test-role-users.sh) | 给本地 console-api 种入 FE e2e RBAC matrix 期望的 2 个租户角色用户。 |
| [setup-mvnd.sh](setup-mvnd.sh) | 安装本地构建脚本使用的固定版本 Maven Daemon。 |
| [sim-harness.sh](sim-harness.sh) | — 可重复执行的 sim / 真实数据验证 harness。 |
| [spark-e2e-runtime.py](spark-e2e-runtime.py) | 为真实 Spark E2E 准备受校验缓存，并向 Maven 传入隔离运行时。 |
| [sql/check-biz-shard-initialization-state.sql](sql/check-biz-shard-initialization-state.sql) | 供 [provision-biz-shard.sh](provision-biz-shard.sh) 使用的配套 SQL。 |
| [sql/check-biz-shard-ready.sql](sql/check-biz-shard-ready.sql) | 供 [provision-biz-shard.sh](provision-biz-shard.sh) 使用的配套 SQL。 |
| [sql/check-database-ready.sql](sql/check-database-ready.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/count-active-job-partitions.sql](sql/count-active-job-partitions.sql) | 供 [batch-consume-load.sh](batch-consume-load.sh) 使用的配套 SQL。 |
| [sql/count-batch-schema-tables.sql](sql/count-batch-schema-tables.sql) | 供 [health-check-infra.sh](health-check-infra.sh) 使用的配套 SQL。 |
| [sql/count-biz-schema-tables.sql](sql/count-biz-schema-tables.sql) | 供 [provision-biz-shard.sh](provision-biz-shard.sh) 使用的配套 SQL。 |
| [sql/count-business-tenant-rows.sql](sql/count-business-tenant-rows.sql) | 供 [sim-harness.sh](sim-harness.sh) 使用的配套 SQL。 |
| [sql/count-job-partitions.sql](sql/count-job-partitions.sql) | 供 [batch-consume-load.sh](batch-consume-load.sh) 使用的配套 SQL。 |
| [sql/count-recent-online-workers.sql](sql/count-recent-online-workers.sql) | 供 [sim-harness.sh](sim-harness.sh) 使用的配套 SQL。 |
| [sql/count-successful-job-partitions.sql](sql/count-successful-job-partitions.sql) | 供 [batch-consume-load.sh](batch-consume-load.sh) 使用的配套 SQL。 |
| [sql/create-forensic-replay-schema.sql](sql/create-forensic-replay-schema.sql) | 每次回放使用独占 namespace；若意外重名则失败，绝不覆盖先前保留的现场。 |
| [sql/delete-expired-waiting-retries.sql](sql/delete-expired-waiting-retries.sql) | DANGER: 删除已过期 WAITING retry 记录。核对保留窗口和目标数据库，避免丢失恢复线索。 |
| [sql/disable-stage5b-fault-channel.sql](sql/disable-stage5b-fault-channel.sql) | Stage 5b 专属故障注入配置退场；按命名所有权定位，保留运行、分发与健康历史。 |
| [sql/drop-forensic-replay-schema.sql](sql/drop-forensic-replay-schema.sql) | DANGER: 删除取证回放 schema 及其全部对象。仅限本地回放库，先确认 schema 名称和备份状态。 |
| [sql/ensure-business-database.sql](sql/ensure-business-database.sql) | 供 [provision-biz-shard.sh](provision-biz-shard.sh) 使用的配套 SQL。 |
| [sql/ensure-sim-atomic-prerequisites.sql](sql/ensure-sim-atomic-prerequisites.sql) | 供 [sim-harness.sh](sim-harness.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/batch-upsert.sql](sql/import-copy-benchmark/batch-upsert.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/copy-stage.sql](sql/import-copy-benchmark/copy-stage.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/copy-target.sql](sql/import-copy-benchmark/copy-target.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/create-index-c01.sql](sql/import-copy-benchmark/create-index-c01.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/create-index-c02-n01.sql](sql/import-copy-benchmark/create-index-c02-n01.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/create-index-c03-c04.sql](sql/import-copy-benchmark/create-index-c03-c04.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/create-schema.sql](sql/import-copy-benchmark/create-schema.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/create-stage-table.sql](sql/import-copy-benchmark/create-stage-table.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/create-table.sql](sql/import-copy-benchmark/create-table.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/drop-table.sql](sql/import-copy-benchmark/drop-table.sql) | DANGER: 删除 COPY benchmark 专用表及其数据。仅限隔离的 benchmark 数据库。 |
| [sql/import-copy-benchmark/merge-stage.sql](sql/import-copy-benchmark/merge-stage.sql) | 供 [import-copy-worth-benchmark.sh](import-copy-worth-benchmark.sh) 使用的配套 SQL。 |
| [sql/import-copy-benchmark/truncate-table.sql](sql/import-copy-benchmark/truncate-table.sql) | DANGER: 清空 COPY benchmark 专用表。仅限隔离的 benchmark 数据库，确认表名后执行。 |
| [sql/insert-flyway-history.sql](sql/insert-flyway-history.sql) | 供 [apply-pending-flyway-migrations.sh](apply-pending-flyway-migrations.sh) 使用的配套 SQL。 |
| [sql/insert-forensic-day-audit.sql](sql/insert-forensic-day-audit.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/insert-forensic-job-instance.sql](sql/insert-forensic-job-instance.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/insert-forensic-replay-request.sql](sql/insert-forensic-replay-request.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/pg-ha-drill/count-marker.sql](sql/pg-ha-drill/count-marker.sql) | 供 [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) 使用的配套 SQL。 |
| [sql/pg-ha-drill/create-marker-table.sql](sql/pg-ha-drill/create-marker-table.sql) | 供 [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) 使用的配套 SQL。 |
| [sql/pg-ha-drill/insert-marker.sql](sql/pg-ha-drill/insert-marker.sql) | 供 [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) 使用的配套 SQL。 |
| [sql/pg-ha-drill/is-primary.sql](sql/pg-ha-drill/is-primary.sql) | 供 [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) 使用的配套 SQL。 |
| [sql/pg-ha-drill/is-streaming-standby.sql](sql/pg-ha-drill/is-streaming-standby.sql) | 供 [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) 使用的配套 SQL。 |
| [sql/pg-ha-drill/promote-standby.sql](sql/pg-ha-drill/promote-standby.sql) | 供 [pg-replica-failover-drill.sh](pg-replica-failover-drill.sh) 使用的配套 SQL。 |
| [sql/provision-single-job-workflow.sql](sql/provision-single-job-workflow.sql) | 保留父进程导出的所选环境，同时避免 |
| [sql/reload-postgres-config.sql](sql/reload-postgres-config.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/reset-business-runtime.sql](sql/reset-business-runtime.sql) | 供 [sim-harness.sh](sim-harness.sh) 使用的配套 SQL。 |
| [sql/reset-platform-runtime.sql](sql/reset-platform-runtime.sql) | 供 [sim-harness.sh](sim-harness.sh) 使用的配套 SQL。 |
| [sql/select-applied-flyway-versions.sql](sql/select-applied-flyway-versions.sql) | 供 [apply-pending-flyway-migrations.sh](apply-pending-flyway-migrations.sh) 使用的配套 SQL。 |
| [sql/select-current-database-size.sql](sql/select-current-database-size.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/select-current-wal-lsn.sql](sql/select-current-wal-lsn.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/select-forensic-export-sha.sql](sql/select-forensic-export-sha.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/select-forensic-trigger-pairs.sql](sql/select-forensic-trigger-pairs.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/select-latest-replay-instance-status.sql](sql/select-latest-replay-instance-status.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/select-launched-job-instance-id.sql](sql/select-launched-job-instance-id.sql) | 供 [sdk-control-e2e.sh](sdk-control-e2e.sh) 使用的配套 SQL。 |
| [sql/select-postgres-health.sql](sql/select-postgres-health.sql) | 供 [health-check-infra.sh](health-check-infra.sh) 使用的配套 SQL。 |
| [sql/select-postgres-recovery-state.sql](sql/select-postgres-recovery-state.sql) | 供 [health-check-infra.sh](health-check-infra.sh) 使用的配套 SQL。 |
| [sql/select-postgres-replica-lag.sql](sql/select-postgres-replica-lag.sql) | 供 [health-check-infra.sh](health-check-infra.sh) 使用的配套 SQL。 |
| [sql/select-replay-snapshot.sql](sql/select-replay-snapshot.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/select-single-job-workflow-id.sql](sql/select-single-job-workflow-id.sql) | 供 [replay-forensic-bundle.sh](replay-forensic-bundle.sh) 使用的配套 SQL。 |
| [sql/select-wal-lsn-difference.sql](sql/select-wal-lsn-difference.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/select-workflow-definition-id.sql](sql/select-workflow-definition-id.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/select-workflow-run-by-request.sql](sql/select-workflow-run-by-request.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/set-checkpoint-timeout.sql](sql/set-checkpoint-timeout.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/set-max-wal-size.sql](sql/set-max-wal-size.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/show-checkpoint-timeout.sql](sql/show-checkpoint-timeout.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/show-max-wal-size.sql](sql/show-max-wal-size.sql) | 供 [pg-write-parameter-matrix.sh](pg-write-parameter-matrix.sh) 使用的配套 SQL。 |
| [sql/strict-count-atomic-invalid-task-type.sql](sql/strict-count-atomic-invalid-task-type.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-count-atomic-jobs.sql](sql/strict-count-atomic-jobs.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-count-atomic-missing-task-type.sql](sql/strict-count-atomic-missing-task-type.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-count-audits.sql](sql/strict-count-audits.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-count-tenant-instances.sql](sql/strict-count-tenant-instances.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-count-traced-audits.sql](sql/strict-count-traced-audits.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-cursor-instance-ids.sql](sql/strict-cursor-instance-ids.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-dominant-tenant.sql](sql/strict-dominant-tenant.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-job-instance-fingerprint.sql](sql/strict-job-instance-fingerprint.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-offset-instance-ids.sql](sql/strict-offset-instance-ids.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/strict-recent-audits.sql](sql/strict-recent-audits.sql) | 供 [strict-verify.sh](strict-verify.sh) 使用的配套 SQL。 |
| [sql/upsert-business-tenant-placement.sql](sql/upsert-business-tenant-placement.sql) | 供 [sim-harness.sh](sim-harness.sh) 使用的配套 SQL。 |
| [sql/validate-seed-cleanup-business.sql](sql/validate-seed-cleanup-business.sql) | DANGER: 删除 validate-seed 业务夹具数据。仅限隔离本地测试库，并按 fixture pattern 精确筛选。 |
| [sql/validate-seed-cleanup-platform.sql](sql/validate-seed-cleanup-platform.sql) | DANGER: 删除 validate-seed 平台夹具数据。仅限隔离本地测试库，并按 fixture pattern 精确筛选。 |
| [sql/validate-seed-count-column.sql](sql/validate-seed-count-column.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-job-definition-baseline.sql](sql/validate-seed-count-job-definition-baseline.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-job-definition-probes.sql](sql/validate-seed-count-job-definition-probes.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-scheduled-trigger-requests.sql](sql/validate-seed-count-scheduled-trigger-requests.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-trigger-outbox.sql](sql/validate-seed-count-trigger-outbox.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-trigger-probes.sql](sql/validate-seed-count-trigger-probes.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-trigger-request.sql](sql/validate-seed-count-trigger-request.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-count-workflow-nodes.sql](sql/validate-seed-count-workflow-nodes.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-delete-workflow-node-probe.sql](sql/validate-seed-delete-workflow-node-probe.sql) | DANGER: 删除 workflow node probe 测试夹具。仅限隔离本地测试库，并核对 probe ID。 |
| [sql/validate-seed-disable-job-definition.sql](sql/validate-seed-disable-job-definition.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-insert-workflow-node-probe.sql](sql/validate-seed-insert-workflow-node-probe.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-process-fixture.sql](sql/validate-seed-process-fixture.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-constraint-definition.sql](sql/validate-seed-select-constraint-definition.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-constraint-type.sql](sql/validate-seed-select-constraint-type.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-flyway-success.sql](sql/validate-seed-select-flyway-success.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-job-instance-status.sql](sql/validate-seed-select-job-instance-status.sql) | 本地构建、服务生命周期与验收的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/validate-seed-select-job-instance-tenant.sql](sql/validate-seed-select-job-instance-tenant.sql) | 本地构建、服务生命周期与验收的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/validate-seed-select-job-task-errors.sql](sql/validate-seed-select-job-task-errors.sql) | 本地构建、服务生命周期与验收的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/validate-seed-select-job-task-statuses.sql](sql/validate-seed-select-job-task-statuses.sql) | 本地构建、服务生命周期与验收的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/validate-seed-select-trigger-outbox-state.sql](sql/validate-seed-select-trigger-outbox-state.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-trigger-outbox-status.sql](sql/validate-seed-select-trigger-outbox-status.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-trigger-request-status.sql](sql/validate-seed-select-trigger-request-status.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-workflow-definition-id.sql](sql/validate-seed-select-workflow-definition-id.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-select-workflow-run-tenant.sql](sql/validate-seed-select-workflow-run-tenant.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-upsert-cron-job.sql](sql/validate-seed-upsert-cron-job.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-upsert-dispatch-fixture.sql](sql/validate-seed-upsert-dispatch-fixture.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-upsert-settlement-batch.sql](sql/validate-seed-upsert-settlement-batch.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [sql/validate-seed-upsert-strict-workflow.sql](sql/validate-seed-upsert-strict-workflow.sql) | 供 [validate-seed-scenarios.sh](validate-seed-scenarios.sh) 使用的配套 SQL。 |
| [start-all.sh](start-all.sh) | 启动当前工作树的本地 Java 服务并管理运行记录。 |
| [stop-all.sh](stop-all.sh) | 按进程归属停止当前工作树的本地 Java 服务。 |
| [strict-verify.sh](strict-verify.sh) | 在线 strict 验证。 |
| [sync-env-topics.sh](sync-env-topics.sh) | — 把 .env.example 的 KAFKA_TOPICS 同步到本地 gitignored 的 .env.prod / .env.local。 |
| [validate-seed-scenarios.sh](validate-seed-scenarios.sh) | 标准化种子数据 + ADR-010 全链路 + 多租隔离 + 异常路径验证脚本。 |
| [verify-biz-shard.sh](verify-biz-shard.sh) | P2 tenant-routing 基础设施验证:起两片真实 PG,跑活体路由测试证明租户落到选定实例。 |
| [watchdog.sh](watchdog.sh) | 本地 java -jar 模式下的 worker 进程守护。 |
