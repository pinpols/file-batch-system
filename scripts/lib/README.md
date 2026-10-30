# Shell 公共库

这里放本仓库脚本共享的 shell helper。

- `env-common.sh`：加载 `.env` / `.env.local` 并导出公共环境变量；裸 JVM 启动时将
  Compose 的 PostgreSQL、replica 和 Redis 服务地址映射为宿主机地址。
- `logging.sh`：日志目录、当前日志软链和 PID 文件辅助函数。
- `process.sh`：进程探测、停止和 PID 文件辅助函数。
- `sdk-e2e-common.sh`：SDK E2E 脚本共享函数。
- `sql/`：共享 SDK E2E 使用的参数化 PostgreSQL 探针与清理语句。

这些文件供其他脚本 `source`，不建议直接执行。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [business-db-bootstrap.sh](business-db-bootstrap.sh) | 业务库初始化公共入口。只能被 source，不要直接执行。 |
| [console-api-readiness.sh](console-api-readiness.sh) | 仅判断 Console API 是否已响应 Actuator JSON；依赖 DOWN 时聚合健康端点可返回 HTTP 503。 |
| [destructive-ops.sh](destructive-ops.sh) | 本地维护/仿真脚本的破坏性操作边界。生产运维命令不应调用此 helper 绕过自身审批流程。 |
| [env-common.sh](env-common.sh) | 公共环境入口。只能被 source,不要直接执行。 |
| [gate-result.sh](gate-result.sh) | 本地提交检查与 CI 门禁共用的分组、模式控制和结果输出格式。 |
| [local-lifecycle-lock.sh](local-lifecycle-lock.sh) | 串行化本地构建、启停操作，避免端口探测和 PID 文件更新互相覆盖。 |
| [logging.sh](logging.sh) | 统一本地日志布局:。 |
| [minio-mc.sh](minio-mc.sh) | 使用固定版本的 MinIO 客户端；不依赖 MinIO 服务镜像内置 mc。 |
| [postgres-client.py](postgres-client.py) | 为运维和压测脚本提供一个最小的 psql 兼容执行入口。 |
| [process.sh](process.sh) | 可执行: source scripts/lib/process.sh; process_listen_pids <port>。 |
| [python-runtime.sh](python-runtime.sh) | Shell 脚本共用的 Python 运行时选择器。 |
| [runtime-defaults.sh](runtime-defaults.sh) | 宿主机脚本与精简 /bin/sh 初始化容器共用的运行时默认值。 |
| [sdk-e2e-common.sh](sdk-e2e-common.sh) | 可复用的 SDK × 真 orchestrator 端到端测试函数库。 |
| [sql/check-database-ready.sql](sql/check-database-ready.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/cleanup-sdk-e2e-worker.sql](sql/cleanup-sdk-e2e-worker.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/cleanup-sdk-e2e.sql](sql/cleanup-sdk-e2e.sql) | 危险：仅在停止生产方后，清理明确归本 SDK 租户/作业/受管 Workflow 夹具所有的记录。 |
| [sql/count-echo-job.sql](sql/count-echo-job.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/count-sdk-e2e-unsettled-launch.sql](sql/count-sdk-e2e-unsettled-launch.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/count-worker.sql](sql/count-worker.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/provision-sdk-e2e-workflow.sql](sql/provision-sdk-e2e-workflow.sql) | 删除在事务中执行；先导出所需证据。目标范围不包含其他 Workflow 或租户。 |
| [sql/select-job-instance-status.sql](sql/select-job-instance-status.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/select-latest-job-instance-id-after.sql](sql/select-latest-job-instance-id-after.sql) | 共享脚本函数的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-latest-job-instance-id.sql](sql/select-latest-job-instance-id.sql) | 共享脚本函数的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-latest-task-for-instance.sql](sql/select-latest-task-for-instance.sql) | 共享脚本函数的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-launched-job-instance-id.sql](sql/select-launched-job-instance-id.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/select-sdk-e2e-workflow-id.sql](sql/select-sdk-e2e-workflow-id.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/select-task-control-state.sql](sql/select-task-control-state.sql) | 共享脚本函数的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-worker-status.sql](sql/select-worker-status.sql) | 共享脚本函数的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/upsert-api-key.sql](sql/upsert-api-key.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
| [sql/upsert-echo-job.sql](sql/upsert-echo-job.sql) | 供 [sdk-e2e-common.sh](sdk-e2e-common.sh) 使用的配套 SQL。 |
