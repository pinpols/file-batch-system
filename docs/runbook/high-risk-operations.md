# 高风险操作治理

适用范围：仓库脚本、SQL、CI 清理步骤和运维 Runbook 中可能删除文件/磁盘数据、数据库数据、Kafka 消息/Topic、S3 对象、Redis/Valkey 键、Docker 资源或 Kubernetes 工作负载的命令。

## 原则

1. 删除命令必须有明确目标范围；不能用生产凭据、远程 Docker context 或未确认的默认连接执行本地清理。
2. 破坏性入口默认预览或拒绝执行。确认需要同时指出目标，而不是只传通用 `--force` / `--yes`。
3. 生产操作由生产变更审批、备份和最小权限控制；本地脚本中的保护不替代 DB 权限、IAM、Kafka ACL、Redis ACL 或部署平台审批。
4. 临时产物清理只允许删除由当前脚本通过 `mktemp` 创建并记录的文件，或当前脚本创建的私有临时目录；不得把用户输入、仓库根目录或任意环境变量直接拼入 `rm -rf`。
5. 新增或扩大删除操作（包括 `rm`、`find -delete`、`mkfs`/块设备命令、服务端删除和 Kubernetes 删除），必须审查目标、确认方式、幂等性、影响范围和恢复方式，并更新 `scripts/ci/baselines/destructive-ops-baseline.json`。

## 仓库内入口与防护

| 资源 | 当前破坏性入口 | 防护与使用边界 |
|---|---|---|
| 文件 / Docker | `scripts/local/cleanup-disk.sh` | 默认预览；执行需 `--apply --confirm-root <仓库目录名>`。Docker 资源清理拒绝远程 context。按 `test-residue` / `build-cache` / `safe` 分批；匿名卷、观测卷、复用测试容器需额外显式选项。业务分片仅按测试所有权标签清理；脚本不执行镜像删除，保护应用及基础环境镜像。 |
| 本地日志归档 | `scripts/local/manage-logs.py`（`cleanup-disk.sh` 日志选项委托） | 默认只读预览；执行须明确当前工作树绝对路径。归档逐文件 SHA-256 校验并同步落盘后才移除未变更原文，保护 current、占用、latest、`.keep` 和文档引用；仅显式 `--prune-archives` 才删除到期且经校验的受管包。恢复从包内原路径及清单核对，过期包删除后不能靠本机恢复。 |
| 本地生命周期锁 | `scripts/lib/local-lifecycle-lock.sh` | 仅在 `${TMPDIR}` 下操作由当前仓库路径哈希和当前 UID 派生的锁目录；失效锁需确认 owner PID 已退出后才回收，回收过程有独立恢复目录串行化，不触碰仓库业务数据。 |
| 本地 HA 故障演练 | `scripts/local/pg-replica-failover-drill.sh`、`scripts/local/redis-sentinel-ha-drill.sh` | 仅接受本机 Docker context；每次生成唯一隔离 Compose project，失败先打印日志，退出清理仅作用于该 project 的容器、网络和专用卷。不得用生产/共享 Docker context 执行。 |
| Docker Compose 栈 | `scripts/docker/reset-dev.sh` | 默认预览；执行需 `--apply` 和输入 project 名或 `--yes`；拒绝生产类 project 名和远程 Docker context，只按 Compose 标签清理。 |
| GitHub Actions 临时 SonarQube | `.github/workflows/sonar-gate.yml` | 每个 job 仅启动固定名称 `sonarqube-batch` 的临时容器，退出时只删除该容器；不清理镜像、卷或其他 Docker 资源。端口只绑定 runner loopback，容器限制 6 GiB/3 CPU，报告以短期 Actions artifact 保存。 |
| Compose CD 部署临时文件 | `.github/workflows/compose-cd.yml` | SSH 私钥和固定 host key 数据只写入当前 GitHub-hosted ephemeral runner 的 `$RUNNER_TEMP`，部署后删除；远端只删除当前 `GITHUB_RUN_ID` 上传并已解包的 release archive，不删除 release 目录、stable manifest 或服务数据。 |
| PostgreSQL sim reset | `scripts/sim/00-reset-runtime.sh`、`scripts/local/sim-harness.sh` | 仅允许本机 Docker socket 上当前 Compose project 管理的 PG 容器。reset SQL 要求 `batch.destructive_ops=sim-reset` 会话标记；单独直接执行 SQL 会失败。`all` 会先确认平台库和业务库已存在，不创建/删除数据库；会清平台/业务运行态，包括业务日与 scheduled occurrence、预期项、等待和回放运行记录；保留日历、依赖定义、expectation 定义和 catch-up 控制配置。 |
| SIM 入站输入清理 | `scripts/local/sim-harness.sh` | 只在 SIM 专用 SFTP 容器内删除 ta/tb/tc 的三种种子文件名，并在 SIM 启动前复核无残留；只在本地 MinIO `ingress/ta/` 下按 Stage 25/26 生成名正则先盘点、再删除并复核。保留其他 SFTP 文件、MinIO 对象和出站产物；源输入可由 SIM 阶段重新生成。不得扩大到整个目录或 bucket。 |
| PostgreSQL DR drill | `scripts/db/backup/dr-drill.sh` | 默认恢复至 `_dr` 旁路库；所有模式要求本机 Compose PG 容器。原地 DROP/重建还要求 `--yes` 和精确 `--confirm-databases <platform>,<business>`，生产/预发布命名库拒绝执行。 |
| PostgreSQL / Kafka / MinIO 四日清理 | `scripts/sim-4day/00-clean.sh` | 清空 platform/business 运行数据、删除 `batch.*` Kafka 历史消息并清空整个 MinIO bucket。只允许本机 Compose project 的 PG/Kafka/MinIO 容器和仓库默认本地端点；SQL 有独立 session guard。运行前必须确认 bucket 可丢弃、应用处于静默状态。 |
| Kafka / MinIO / PostgreSQL 压测清理 | `load-tests/scripts/cleanup-load-test-environment.sh` | 默认只诊断/预览；执行需 `--apply --confirm-project <当前 Compose project>`。远程 Docker、非本地 PG host 和非本地对象存储端点拒绝执行。`--kafka-reset-topics` 会删并重建 topic；`--postgres-reclaim` 会执行锁表的 `VACUUM FULL`。 |
| S3 兼容服务迁移 PoC | `scripts/local/s3-compatible-minio-migration.sh` | 用随机桶名，只清理脚本确认创建成功的桶；创建失败或桶已存在时不会在退出 trap 中删除既有桶。目标 endpoint 和凭据仍须由操作者确认是专用测试账号/环境。 |
| Redis / Valkey | 当前没有仓库脚本调用 `FLUSHDB` / `FLUSHALL`；Runbook 有按 key 的 `DEL` 示例 | 禁止全库 flush。清锁前先核对实例、DB index、key 前缀、租约状态和 owner；只对确认过期的精确 key 执行删除。不要把未经审阅的 `SCAN` 结果直接 pipe 到 `DEL`。 |
| Kubernetes / Helm | `scripts/ha/failover-drill.sh` 删除基础服务 Pod；`run-full-regression.sh` 的 live verification 会卸载并重建专用 Helm release | 故障演练要求精确确认 kube context 和 namespace 清单，拒绝生产/预发布 context。部署验证 live 模式要求 `BATCH_DEPLOY_VERIFICATION_CONFIRM_TARGET=<context>/<namespace>/<release>`；生产 context 拒绝。 |

## SQL 和 CI

- Sim 29（`scripts/sim/29-tenant-config-scenarios.sh`）退出时仅清理本次运行通过 `mktemp` 创建的 Cookie 文件和私有临时目录；路径不接受外部覆盖，Cookie 未创建时跳过删除。目录中仅含下载的模板、预览响应和校验产物，删除可重复执行，重新运行即可生成；不执行 Apply，不删除数据库、对象存储或既有文件，因此无需业务数据恢复或交互确认。两处清理已审查并登记基线。
- Sim 平台 reset 两个入口共用 `scripts/local/sql/reset-platform-runtime.sql`。补入独立触发请求、任务和审计等原 `00-reset` 已覆盖的表，不依赖外键反向清理；保持本机 Compose 校验、`sim-reset` 会话守护和 `batch` schema 限定。仅用于可丢弃的仿真运行数据，配置与身份表保留；禁止连接生产库，清理前先静默应用。删除运行证据不可恢复，需要留档时先导出报告。
- Workflow/每日连续性加入的 `batch_day_instance`、等待/回放运行记录、`calendar_dependency_wait`、`job_dependency_waiting_launch`、`scheduled_occurrence`、coverage、`expected_work_occurrence` 和相关审计历史均属于可重建 SIM 运行态，reset 在上述本机与会话守卫内清理；保留 `calendar_dependency`、`job_dependency`、文件/事件 expectation 定义及 `batch_day_catchup_control` 操作配置。不得把该 reset 用于保存生产/预发布运行历史的数据库。
- SDK E2E Workflow 清理使用显式租户、Job code 和派生的 `JOB-WF-<job.id>` Workflow code 限定，只清理该 fixture 的运行记录、请求和定义；复用同租户的其他 Workflow 不属于目标。相关新增删除项已在审查目标范围后登记增量基线。
- `scripts/ci/check-db-scripts-safety.sh` 扫描维护、sim、local、load-test SQL。`DROP`、`TRUNCATE`、`DELETE FROM` 和约束变更必须在文件头部标明风险及目标环境；seed SQL 也不豁免。
- `scripts/local/pre-commit-checks.sh` 将本次门禁错误汇总写入由 `mktemp` 创建的独立临时文件，并仅在退出时删除该文件；路径由脚本创建，不接受调用方覆盖，清理不触碰仓库或服务数据。
- `scripts/local/audit-runtime-warnings.sh` 的应用日志清单和单文件 WARN 缓冲分别由 `mktemp` 创建，创建成功后即登记退出清理；输入日志和策略文件不在清理范围内。盘点/读取失败返回输入错误，已有告警分类保持阻断。
- `scripts/local/pre-push-sdk-checks.sh` 的 Java 治理与 clean compile 各自在 `mktemp -d` 创建的一次性 detached worktree 中运行；正常路径由 `git worktree remove` 清理，worktree 注册失败时仅对脚本刚创建且仍为空的精确临时目录执行 `rmdir`。目标不接受外部输入，不触碰仓库工作树或业务数据，失败后可直接重跑，无需业务恢复。
- `scripts/ci/check-destructive-ops-governance.py` 盘点普通及强制 `rm`、PG `DROP`/`TRUNCATE`/`DELETE`/`dropdb`/`pg_restore --clean`、S3 删除/同步删除、Kafka topic/group/record 删除、Redis/Valkey `DEL`/`UNLINK`/`FLUSH*`，以及 Docker/Kubernetes 清理操作；支持识别反斜杠续行和 `minio_mc` 包装器。扫描范围包含 `scripts/`、`load-tests/scripts/`、`db/`、`deploy/`、`.github/workflows/` 和 `docs/runbook/`。相对 `scripts/ci/baselines/destructive-ops-baseline.json` 新增或扩大时 PR/Full Gate 失败，审查后才更新基线。
- 作业监控清理按实例保留期，仅删除即将清理的 `job_instance` 对应的 `job_monitoring_alert_claim`；其无外键是为适配按业务日分区的实例表，清理在删除实例前执行。V225/V228 只替换本功能先前版本创建的 CHECK 约束，先验证扩展约束再删除旧约束，不删除业务行或告警事件。以上新增操作已纳入增量基线。
- V234 替换 `config_change_log` 的 action CHECK 约束以纳入 `REVOKE` 审计动作；只删除旧约束定义，不删除表或业务记录，随后创建并验证新约束。该迁移的 `DROP CONSTRAINT` 已登记在增量基线。
- V289/V291 分别把 WorkflowRun→WorkflowDefinition、JobInstance→JobDefinition 的单 ID 外键替换为 `(tenant_id, id)` 复合外键；只移除这两个具名旧约束，不删除表或业务行。同一条 ALTER 原子完成替换，V289 的验证也属于同一次 Flyway 事务，失败时回滚；V291 的分区外键直接验证。本整合仅接受全新数据库部署，运行前不得已有业务写入；大表旧库在线升级不在其适用范围内。当前迁移文件哈希与 2026-10-18 有效空库 SIM 的记录相同，空库迁移和租户引用负例已有验证；只将这两个文件各 1 项约束移除登记到基线，不扩大其它操作计数。
- V292 将热 `pipeline_progress` 绑定到同租户 Pipeline，父删除采用 RESTRICT，禁止级联丢弃 checkpoint；新增唯一索引后，FK 在同一 Flyway 事务内 ADD NOT VALID 再 VALIDATE，提交时已验证。正式归档服务先锁定父/位点、复制并核对冷证据，再删热子和父；两个保留期直接清理脚本遇到位点候选，使用 SQLSTATE 55000 异常和 ON_ERROR_STOP 在任何写入前非零退出，要求转走 Orchestrator 归档服务。它们没有新增 checkpoint 直接删除，也不使用 psql 17 会忽略状态参数的 `\quit 1` 作为失败保证。
- V293 只替换 `workflow_node`、`workflow_edge`、`workflow_launch_wait` 各一个已知单ID父定义外键：先添加并验证 `(tenant_id, workflow_definition_id)` 复合引用，再移除冗余旧约束。整个迁移在同一Flyway事务内提交，失败回滚，父删除仍为NO ACTION，不删除业务数据，也不改变草稿JOB编码的弱引用规则。等待表增加关联索引，节点/边复用已有复合唯一索引。按全新库部署，在应用写入前执行；旧大表在线升级不属于本轮验证。经此目标/事务/恢复审查，仅登记V293三项约束移除，未扩大其它基线或检查器例外。
- 测试数据清理和显式租户 wipe 仍是经原入口授权的销毁：两个 load-test SQL 只删除本 runId 已选中的 Pipeline 位点，validate-seed 只删除已选 fixture Pipeline 位点；两个 wipe 按原 tenant IN / NOT IN 边界先删热位点再删父，未扩大租户清单、没有新增 cascade 或默认远程目标。数据销毁不可恢复，需要证据时先导出；同一事务中失败即回滚。仅为两个 wipe 各登记1项 DELETE（60→61），保留其它基线项；不得据此宣称整个 wipe 流程已经完成真实环境验收。
- V294在同一Flyway事务内先新增并验证Job等待与容量等待到原请求的`(tenant_id,request_id)`外键，再移除容量等待旧的级联约束；两者删除动作统一NO ACTION，禁止父删除静默丢弃恢复和终态审计。仅移除一个已知旧约束，无业务数据搬移或删除；新库部署须在应用写入前完成，失败回滚。仅登记V294这一项约束移除。
- 两个保留期清理入口共用`cleanup-trigger-request-retention.sql`，必须通过原入口的psql `-f`执行且位于同一事务；include不对外独立调用。单批默认最多1000个原请求（允许1..10000），只选择到期终态，先锁原请求，再显式清到期终态等待，最后清无等待父请求。可恢复FORWARD_FAILED、活跃/近期等待、冷热运行及触发/预期/补跑审计关联均保留；30日/1小时是最小年龄窗口，非到期即必删。两个直接实例清理入口发现checkpoint或冷热Workflow关联候选时在任何写入前拒绝，转Orchestrator完整归档。非法参数或约束冲突非零退出、事务回滚，已提交的历史清理不可恢复，执行前需确认目标库、静默写方并导出必要证据。本次仅登记共享include三项删除并收紧原入口减少的删除计数；其它弱引用账本并发及生产规模验收仍开放。
- 显式tenant wipe仅在原有tenant IN/NOT IN范围内增加两类等待的子先父清理（各61→63项）；validate-seed同既有fixture原请求范围增加Job等待清理（25→26项）。SDK清理先固定自有Workflow运行ID，在同tenant和精确Job/受管Workflow边界内清理根及内部子请求的两类等待（15→17项），实例先于原请求删除；同租户其它Workflow和其它租户必须保留。以上为经原入口授权的fixture/租户销毁，执行前停止对应写方，失败整事务回滚，提交后需从备份或重新装载恢复；没有扩大租户清单、没有绕过FK，也不代表完整wipe已验收。只登记本次具体增量，不修改检查器或其它路径上限。
- 该基线是增量治理，不是命令沙箱：无法约束开发者在仓库外执行命令，也无法阻止有 DB 超级用户/云管理员权限的人绕过脚本。生产必须使用独立账号、最小权限和变更审批。

## 操作前清单

- 确认目标环境、地址、数据库/桶/Topic/容器名称；禁止仅凭脚本名推定是测试环境。
- 执行 dry-run/preview，检查实际将删除的对象和数量。
- 对不可重建数据确认备份可读、恢复责任人和回滚步骤；无法恢复的操作必须有审批记录。
- 涉及 PG 的清理需评估事务锁、级联影响、租户范围和归档保留；涉及 Kafka 需确认消费者已停写/追平；涉及对象存储需核对 bucket/prefix 与生命周期策略。
- 执行后检查目标计数、服务健康、业务状态及审计记录；不要用“命令退出码为 0”作为唯一成功标准。
