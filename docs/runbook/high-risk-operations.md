# 高风险操作治理

适用范围：仓库脚本、SQL、CI 清理步骤和运维 Runbook 中可能删除文件/磁盘数据、数据库数据、Kafka 消息/Topic、S3 对象、Redis/Valkey 键、Docker 资源或 Kubernetes 工作负载的命令。

## 原则

1. 删除命令必须有明确目标范围；不能用生产凭据、远程 Docker context 或未确认的默认连接执行本地清理。
2. 破坏性入口默认预览或拒绝执行。确认需要同时指出目标，而不是只传通用 `--force` / `--yes`。
3. 生产操作由生产变更审批、备份和最小权限控制；本地脚本中的保护不替代 DB 权限、IAM、Kafka ACL、Redis ACL 或部署平台审批。
4. 临时产物清理只允许删除由当前脚本通过 `mktemp` 创建并记录的文件，或当前脚本创建的私有临时目录；不得把用户输入、仓库根目录或任意环境变量直接拼入 `rm -rf`。
5. 新增或扩大删除操作（包括 `rm`、`find -delete`、`mkfs`/块设备命令、服务端删除和 Kubernetes 删除），必须审查目标、确认方式、幂等性、影响范围和恢复方式，并更新 `scripts/ci/destructive-ops-baseline.json`。

## 仓库内入口与防护

| 资源 | 当前破坏性入口 | 防护与使用边界 |
|---|---|---|
| 文件 / Docker | `scripts/local/cleanup-disk.sh` | 默认预览；执行需 `--apply --confirm-root <仓库目录名>`。Docker 资源清理拒绝远程 context。按 `test-residue` / `build-cache` / `safe` 分批；匿名卷、观测卷、复用测试容器需额外显式选项。业务分片仅按测试所有权标签清理；脚本不执行镜像删除，保护应用及基础环境镜像。 |
| 本地生命周期锁 | `scripts/lib/local-lifecycle-lock.sh` | 仅在 `${TMPDIR}` 下操作由当前仓库路径哈希和当前 UID 派生的锁目录；失效锁需确认 owner PID 已退出后才回收，回收过程有独立恢复目录串行化，不触碰仓库业务数据。 |
| 本地 HA 故障演练 | `scripts/local/pg-replica-failover-drill.sh`、`scripts/local/redis-sentinel-ha-drill.sh` | 仅接受本机 Docker context；每次生成唯一隔离 Compose project，失败先打印日志，退出清理仅作用于该 project 的容器、网络和专用卷。不得用生产/共享 Docker context 执行。 |
| Docker Compose 栈 | `scripts/docker/reset-dev.sh` | 默认预览；执行需 `--apply` 和输入 project 名或 `--yes`；拒绝生产类 project 名和远程 Docker context，只按 Compose 标签清理。 |
| PostgreSQL sim reset | `scripts/sim/00-reset-runtime.sh`、`scripts/local/sim-harness.sh` | 仅允许本机 Docker socket 上当前 Compose project 管理的 PG 容器。reset SQL 要求 `batch.destructive_ops=sim-reset` 会话标记；单独直接执行 SQL 会失败。 |
| PostgreSQL DR drill | `scripts/db/backup/dr-drill.sh` | 默认恢复至 `_dr` 旁路库；所有模式要求本机 Compose PG 容器。原地 DROP/重建还要求 `--yes` 和精确 `--confirm-databases <platform>,<business>`，生产/预发布命名库拒绝执行。 |
| PostgreSQL / Kafka / MinIO 四日清理 | `scripts/sim-4day/00-clean.sh` | 清空 platform/business 运行数据、删除 `batch.*` Kafka 历史消息并清空整个 MinIO bucket。只允许本机 Compose project 的 PG/Kafka/MinIO 容器和仓库默认本地端点；SQL 有独立 session guard。运行前必须确认 bucket 可丢弃、应用处于静默状态。 |
| Kafka / MinIO / PostgreSQL 压测清理 | `load-tests/scripts/cleanup-load-test-environment.sh` | 默认只诊断/预览；执行需 `--apply --confirm-project <当前 Compose project>`。远程 Docker、非本地 PG host 和非本地对象存储端点拒绝执行。`--kafka-reset-topics` 会删并重建 topic；`--postgres-reclaim` 会执行锁表的 `VACUUM FULL`。 |
| S3 兼容服务迁移 PoC | `scripts/local/s3-compatible-minio-migration.sh` | 用随机桶名，只清理脚本确认创建成功的桶；创建失败或桶已存在时不会在退出 trap 中删除既有桶。目标 endpoint 和凭据仍须由操作者确认是专用测试账号/环境。 |
| Redis / Valkey | 当前没有仓库脚本调用 `FLUSHDB` / `FLUSHALL`；Runbook 有按 key 的 `DEL` 示例 | 禁止全库 flush。清锁前先核对实例、DB index、key 前缀、租约状态和 owner；只对确认过期的精确 key 执行删除。不要把未经审阅的 `SCAN` 结果直接 pipe 到 `DEL`。 |
| Kubernetes / Helm | `scripts/ha/failover-drill.sh` 删除基础服务 Pod；`run-full-regression.sh` 的 live verification 会卸载并重建专用 Helm release | 故障演练要求精确确认 kube context 和 namespace 清单，拒绝生产/预发布 context。部署验证 live 模式要求 `BATCH_DEPLOY_VERIFICATION_CONFIRM_TARGET=<context>/<namespace>/<release>`；生产 context 拒绝。 |

## SQL 和 CI

- `scripts/ci/check-db-scripts-safety.sh` 扫描维护、sim、local、load-test SQL。`DROP`、`TRUNCATE`、`DELETE FROM` 和约束变更必须在文件头部标明风险及目标环境；seed SQL 也不豁免。
- `scripts/local/pre-commit-checks.sh` 将本次门禁错误汇总写入由 `mktemp` 创建的独立临时文件，并仅在退出时删除该文件；路径由脚本创建，不接受调用方覆盖，清理不触碰仓库或服务数据。
- `scripts/ci/check-destructive-ops-governance.py` 盘点普通及强制 `rm`、PG `DROP`/`TRUNCATE`/`DELETE`/`dropdb`/`pg_restore --clean`、S3 删除/同步删除、Kafka topic/group/record 删除、Redis/Valkey `DEL`/`UNLINK`/`FLUSH*`，以及 Docker/Kubernetes 清理操作；支持识别反斜杠续行和 `minio_mc` 包装器。扫描范围包含 `scripts/`、`load-tests/scripts/`、`db/`、`deploy/`、`.github/workflows/` 和 `docs/runbook/`。相对 `scripts/ci/destructive-ops-baseline.json` 新增或扩大时 PR/Full Gate 失败，审查后才更新基线。
- 该基线是增量治理，不是命令沙箱：无法约束开发者在仓库外执行命令，也无法阻止有 DB 超级用户/云管理员权限的人绕过脚本。生产必须使用独立账号、最小权限和变更审批。

## 操作前清单

- 确认目标环境、地址、数据库/桶/Topic/容器名称；禁止仅凭脚本名推定是测试环境。
- 执行 dry-run/preview，检查实际将删除的对象和数量。
- 对不可重建数据确认备份可读、恢复责任人和回滚步骤；无法恢复的操作必须有审批记录。
- 涉及 PG 的清理需评估事务锁、级联影响、租户范围和归档保留；涉及 Kafka 需确认消费者已停写/追平；涉及对象存储需核对 bucket/prefix 与生命周期策略。
- 执行后检查目标计数、服务健康、业务状态及审计记录；不要用“命令退出码为 0”作为唯一成功标准。
