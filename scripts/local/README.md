# 本地开发脚本

本地 JVM 模式下的启停、构建和测试脚本。

## 脚本列表

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
