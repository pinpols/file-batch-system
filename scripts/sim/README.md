# 多租户业务模拟器

模拟 ta / tb / tc 三个真实租户的 IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC Worker 与 Workflow 编排上下游交互,
本地端到端验证调度链路。

所有顶层 Trigger launch 都必须使用 `workflowDefinitionId`；单 Job 场景从 Job Definition 解析受管单作业 Workflow，复合流程按 Workflow code 解析。Job code 仅用于 Workflow 内部节点和查询，不能作为根触发目标。

## Sim 分组

后端 Sim 分为两个互补分组：

| 分组 | 目标 | 当前入口与边界 |
|---|---|---|
| **业务主链组** | 多租户导入、导出、加工、分发、调度、文件束、批量 claim 和运行中配置快照 | `scripts/local/sim-harness.sh all`；标准阶段 04–30 |
| **可靠性场景组** | 文件完整性与发布原子性、DB 并发幂等、Worker/依赖故障识别、故障隔离与恢复收敛 | 覆盖标准 Sim、模块 IT/E2E 和隔离 staging DR；当前不是独立的第二套 harness。完整矩阵见 [`docs/testing/backend-sim-reliability.md`](../../docs/testing/backend-sim-reliability.md) |

可靠性场景组按故障风险组织验证资产，不表示每项都已进入标准 Sim。现有标准 Sim 的显式硬崩溃场景是 Stage 25；Worker 整队崩溃与 PITR 为单独 DR 脚本；PG failover、Kafka 短暂不可用及 DLQ 重放的 staging 证据仍按 [staging 执行清单](../../docs/runbook/go-live-staging-execution.md)独立验收。组件测试、E2E、Compose Sim 和真实 staging 演练必须分别报告。

可在隔离环境日常回归的故障测试通过 `bash scripts/local/backend-reliability-tests.sh` 执行；`daily-backend-reliability` workflow 每日运行同一入口并归档 Surefire 报告。该套件以 Testcontainers 管理依赖，不连接本地 Compose Sim；PITR、HA failover 和 Worker 整队容器脚本仍需显式隔离目标。

## 设计

| 租户 | 业务 | 4 类 worker job | 上游 source | 下游 sink |
|---|---|---|---|---|
| **ta** | 零售订单 (CUSTOMER / ORDER / SETTLE) | IMPORT × 2 / EXPORT / DISPATCH / Workflow | SFTP /inbound | MinIO `ta/outbound/` + LOCAL archive |
| **tb** | 银行清算 (TRANSACTION / STATEMENT / RECONCILE) | IMPORT / EXPORT / DISPATCH / Workflow | SFTP /inbound | MinIO `tb/outbound/` + MockServer `:1080/tb/*` |
| **tc** | 风控 (RISK_SCORE / RISK_ALERT / REVIEW) | IMPORT / EXPORT / DISPATCH / 3×Workflow | SFTP /inbound | MockServer `:1080/tc/ingest` + SFTP |

租户配置(`job_definition` / `file_template_config` / `file_channel_config` / `workflow_*`)在
`test-data/tenant-config-packages/{ta,tb,tc}-tenant-config-package-test.xlsx`,
**已经完整**,不用我重新写。

模拟器额外补的容器:
- `batch-sim-sftp`(jmcombs/sftp):3 个账户 ta/tb/tc 共享同一容器,各 chroot 到 `/home/<user>/`;网络服务名仍为 `sftp`;镜像支持 amd64/arm64
- `batch-sim-mockserver`(mockserver/mockserver):3 个 HTTP stub(`/tb/callback`, `/tb/ingest`, `/tc/ingest`);网络服务名仍为 `mockserver`

容器内文件校验使用 `SFTP_CONTAINER`，默认值为 `batch-sim-sftp`；若自定义 Compose 实际容器名，需让 Compose 创建或复用该名称并在运行前覆盖变量，不要把网络服务名 `sftp` 用作 `docker exec` 目标。

## 文件清单

```
scripts/sim/
├── README.md                     # 本文档
├── compose.yml                   # 模拟器容器定义(sftp + mockserver)
├── mockserver-stubs/
│   └── expectations.json          # MockServer 启动期 stub 配置
├── 00-reset-runtime.sh            # 把运行态 + biz 数据清回基线,让整套 sim 可重复跑(保留 config/definition)
├── 01-init-biz.sh                 # biz.* 表 + MinIO bucket
├── 02-start-sim.sh                # 起 sftp + mockserver
├── 03-import-tenants.sh           # 导入 ta/tb/tc Excel 配置到 console-api
├── 04-seed-source-data.sh         # 投放假业务 CSV 到 SFTP /inbound/
├── 05-load.sh                     # 中量循环触发 job(默认 5 轮 × 15 job)
├── 06-verify.sh                   # 对账 4 类 worker 产物
├── 29-tenant-config-scenarios.sh  # 场景模板投影 + 上传/预览，不 Apply
├── 30-tenant-config-runtime-edit.sh # 运行中编辑配置的实例快照边界
└── 99-stop.sh                     # 停模拟器容器(volume 保留)
```

## 推荐入口

标准本地与 nightly 验证统一使用 harness。`sim` 会执行 04-30 全部业务主链阶段；26 文件束与
27 batch-claim 会由 harness 临时切换 Worker 配置，结束或异常退出时恢复默认配置。

阶段运行前若核心健康检查触发整体恢复，harness会重新应用该阶段要求的Import skip/checkpoint/bundle或Process batch-claim模式；核心或模式重启命令失败会显式返回，旧健康端点仍200也不能掩盖失败；不能在默认模式下冒充验证。共享本地环境的真实验收应安排独占窗口；调用方可持有已有生命周期锁并传给子脚本，避免其它会话在场景中重建运行包或启停服务。比较累计指标前后必须核对进程启动身份，不跨重启直接相减。
任何阶段未执行、未命中目标路径或未达业务终态都返回失败，不以 `SKIP` 或编排侧部分成功代替。

基线 `05-load.sh` 按租户先触发 IMPORT，并等待该请求关联的 JobInstance 到达 `SUCCESS` 后才触发同租户 EXPORT；IMPORT 启动失败、执行失败或 180 秒内未结束时，该租户的 EXPORT/DISPATCH 跳过并使本轮失败。这样不会因 Trigger 仅返回“已受理”就把异步 IMPORT 当作已完成前置条件。

新增配置简化 SIM 阶段：29 通过 Console API 生成七类租户场景、组合及 ALL 工作簿，不再包含租户 Pipeline 定义，检查 Sheet/列投影，
再执行 upload → preview，**不 Apply 配置**。该矩阵跨越 Console 昂贵操作每用户 10 次/分钟默认限额；遇到 429 时等待限流窗口后有限重试，
不调整或关闭服务端限流；30 在 Atomic shell 任务运行期间通过 Console API 修改默认参数、
重试策略和超时，断言旧实例保持创建时快照、新实例读取新配置，并恢复 SIM 作业定义。它验证执行/重试配置
的实例生效时点，不代表模板/Pipeline/通道配置具备同实例快照，也不替代监控策略动态生效的 PG IT。

### 29/30 数据清理边界

- 29 的本地 Cookie、下载工作簿和响应文件由退出 trap 清理；服务端上传/预览会话使用既有 30 分钟 TTL，不通过清空 Redis 回收。未执行 Apply，不产生作业定义。
- 30 的实例、任务、触发请求、Outbox 和 Console 操作审计由下一次 `all` 的 reset 清理。`00-reset-runtime.sh` 与 harness 共用 `scripts/local/sql/reset-platform-runtime.sql`，不再各自维护平台表清单。报告日志保留作为验证证据。
- 30 在正常结束、断言失败、SIGTERM 或 Ctrl-C 时通过 `finally` 尝试恢复原配置；恢复 API 失败会使阶段失败，不能当作已恢复。SIGKILL/机器断电无法执行恢复。默认 `default-tenant/atomic_shell_demo` 在下一次 `all` 的 `prereq` 中由 `ensure-sim-atomic-prerequisites.sql` 重置为标准 fixture；单独 `reset` 不改定义。自定义 `SIM_ATOMIC_TENANT_ID/SIM_ATOMIC_JOB_CODE` 不受该 fixture 重置覆盖，必须使用专用测试作业并在异常后核对、恢复配置。
- 单独运行 `sim` 或某个阶段不自动 reset；清理共享运行数据前须停止相关应用写入，优先使用含静默步骤的 `all`。

```bash
bash scripts/local/sim-harness.sh all
```

`all` 清运行数据及 SIM 自有的 ta/tb/tc 入站种子；平台 reset 同时清理业务日、Occurrence、预期项和依赖等待等运行态，并保留日历/依赖定义及 catch-up 控制配置。SFTP/MockServer/MinIO 等持久容器和卷继续保留；应用启动前，只删除 SIM 专用 SFTP 入站目录中 `ta-customer-*.csv`、`tb-transaction-*.csv`、`tc-risk-score-*.csv`，并逐个确认删除后无残留；MinIO 只清理 `ingress/ta/` 下由 Stage 25/26 生成的 `sim-stage2e-ckpt-*`、`bundle-a/b-*` 与 `bundle-import-*` 输入，并复核已清空。成功结束 `sim`/`sim-selected` 后会再次执行同一精确清理，避免 Import Worker 在 SIM 结束后反复扫描测试输入；清理失败会使 SIM 失败。失败或被中断的运行保留对象供诊断，下一次 `all` 会在启动应用前清理；其他 bucket 对象和出站产物不在删除范围。MinIO 产物、MockServer 请求与业务行都按本轮 `BATCH_NO` 核对，历史结果不会替本轮通过。`clean-stale-test-env.sh` 只管理孤儿 Testcontainers、带本仓库标签的 routing-sim 分片和 SDK 样例 Worker；超出范围的持久资源会明确报告，清理错误会阻断 SIM。

全新部署前需先在本机 PostgreSQL 容器中创建所选平台库和业务库；`all` 只重置库内运行态，不创建、删除或覆盖数据库。`preflight` 会在 reset 前确认两个目标库都存在且 SQL/JVM URL 指向同名库。

`03-import-tenants.sh` 先在本轮私有目录准备上传副本，由 `sim_mockserver_base_url` 按宿主机/Compose 模式统一已知 SIM API 渠道的目标列和 JSON 地址，再上传 Apply；原模板保留容器地址。准备器仅修改相关单元格，保留其他 ZIP 成员及元数据，重复准备的 SHA-256 稳定。每租户记录实际上传副本的 SHA-256；同指纹重复运行跳过 Apply，端口/运行模式/工作簿变化或存在未登记的既有配置时失败关闭，要求使用干净数据库，不改台账规避检查。本轮副本在成功、失败及信号退出时清理，清理失败返回失败。

直接执行 26/27 时不会自动配置 Worker，也不会跳过；调用方必须先提供脚本头部说明的运行配置。

26 文件束导入中，每个 partition 绑定一份完整来源文件，`partitionCount` 表示束中文件数，
不是对每份文件再次切片的份数。Worker 保留 `bundleSourceFileId` 绑定，PREPROCESS 不做
range 切片，PARSE 不做行取模；普通单文件分片和 checkpoint 多分区保护不变。
本轮实例按 requestId 定位，分区来源必须与上传路径一致，客户业务键带本轮标识，
分别断言两文件为 10/15 行、合计 25 行，防止旧数据掩盖漏数或污染重跑结果。
对应回归位于 `ParseStepPartitionSliceTest`、`PreprocessRangeSliceTest`、
`PreprocessStepObjectLoadIntegrationTest` 和 `ImportPipelineE2eIT`。

Stage 24 支持 `SIM_TRIGGER_REBUILD_WITH_PENDING=1`：初次 fixture 重启保持当前 JAR，待真实 misfire 已持久化后，再由 `restart.sh BUILD=1` 换成当前源码构建。记录前后 JAR SHA-256（必须不同）、原 pending/request/fire/bizDate/载荷快照不变及审批后实际 Root/Job 结果；默认值 0 仍是同构建重启。跨构建模式仅支持 `restart.sh`，运行前保留旧 runtime JAR，不能先覆盖全部构建。只需定向运行 Stage 24，无需全量 SIM。

## 跑通顺序(首次)

```bash
# 前置:主 compose 已 up,BE 全跑(make dev-start)+ make dev-health 全过

bash scripts/sim/01-init-biz.sh           # 一次性:biz.* 表 + MinIO bucket
bash scripts/sim/02-start-sim.sh          # 起 sftp + mockserver
bash scripts/sim/03-import-tenants.sh     # 幂等:相同工作簿指纹只 Apply 一次
export BATCH_NO="sim-manual-$(date +%Y%m%d%H%M%S)"
bash scripts/sim/04-seed-source-data.sh   # 每次跑:假数据投 SFTP
bash scripts/sim/05-load.sh               # 触发 75 个 job(5×15)
sleep 120                                 # 等 worker 跑完一轮
bash scripts/sim/06-verify.sh             # 对账产物
```

MinIO 操作统一通过 `bash scripts/lib/minio-mc.sh ...` 使用独立运行的固定版本
CLI 容器；默认 `bitnamilegacy/minio:2025.7.23-debian-12-r1` 已验证内置 `mc`，
不要在 `batch-minio` server 容器内直接执行 `mc`。

## 日常重跑(已 init 过)

```bash
bash scripts/sim/04-seed-source-data.sh   # 新一批源数据
export BATCH_NO="sim-manual-$(date +%Y%m%d%H%M%S)"
bash scripts/sim/05-load.sh               # 触发
bash scripts/sim/06-verify.sh             # 看产物
```

## 可重复跑(清回基线后重放整套)

多数 stage 按唯一 `batchNo` / `requestId` 断言,天然可重复;但少数 stage 的 COUNT 断言
依赖共享表的累积量(如 `12-export` 读 `biz.customer_account` 全部 ACTIVE 行、`24-trigger`
的 outbox 重试实例计数),**跨 stage / 跨重复运行会累积污染**。跑前 reset 一次即全套可重复:

```bash
bash scripts/sim/00-reset-runtime.sh      # 清运行态(job_/pipeline_/outbox/file_/...)+ biz.* 数据
                                          # 保留:所有 *_definition/*_config/*_template、tenant、
                                          #       console_user、api_key、4 张系统表
bash scripts/sim/04-seed-source-data.sh   # 重新投源数据
export BATCH_NO="sim-manual-$(date +%Y%m%d%H%M%S)"
bash scripts/sim/05-load.sh               # 重放
bash scripts/sim/06-verify.sh
```

> reset **只清运行数据、不删表结构和编排定义**。`03-import-tenants` 指纹保留；同一工作簿自动跳过 Apply，工作簿更新时使用新的干净数据库。
> 脚本是 env-driven 的双栈安全实现:不 source 任何 env 时走单机 `batch-postgres-primary`,
> Citus 拓扑下 `source env-citus.sh` 后自动切到协调器(见 `citus` 分支的 `run-all-citus.sh`)。

## LAN 访问(多机环境 / 手机 / 跨网段)

所有脚本都 env-driven。**一行 source 切到 LAN 模式**:

```bash
source scripts/sim/env-lan.sh          # 自动探测 LAN_HOST(默认本机 en0 IP)
bash scripts/sim/03-import-tenants.sh  # 自动用 LAN URL
bash scripts/sim/05-load.sh
bash scripts/sim/06-verify.sh
```

或手动指定其他主机:

```bash
LAN_HOST=192.168.1.13 source scripts/sim/env-lan.sh
```

env-lan.sh 会 export `CONSOLE_BASE` / `TRIGGER_BASE` / `MOCK_BASE` / `PG_PRIMARY_HOST:PORT` /
`REDIS_HOST:PORT` / `KAFKA_BOOTSTRAP` / `MINIO_URL` / `SFTP_HOST:PORT` 一整套。
03-06 脚本都识别这些(默认 localhost)。

独立手工跑 05/06 时要显式共享同一个 `BATCH_NO`，让校验只看本轮行、对象和请求：

```bash
export BATCH_NO="sim-manual-$(date +%Y%m%d%H%M%S)"
bash scripts/sim/05-load.sh
sleep 120
bash scripts/sim/06-verify.sh
```

**注意端口跟 `.env.local` 对齐**:console 18080 / trigger 18081 / orchestrator 18082 /
PG 15432 / Redis 16379 / Kafka 19092 / MinIO 19000 / SFTP 12222 / MockServer 11080。
如果你这边端口被改过(如 docker-compose.deploy.yml override 到 18090),传 `CONSOLE_PORT=18090` 给 env-lan.sh 即可。

## 调试入口

```bash
# SFTP 内容
docker exec batch-sim-sftp ls -la /home/ta/inbound/ /home/tb/inbound/ /home/tc/inbound/

# MockServer 收到的所有请求
curl -X PUT http://localhost:11080/mockserver/retrieve?type=REQUESTS

# MinIO Web 控制台
open http://localhost:19001    # 账号:minioadmin / minioadmin123

# 业务库
docker exec -it batch-postgres-primary psql -U batch_user -d batch_business
# \dt biz.*
# select tenant_id, count(*) from biz.customer_account group by tenant_id;

# job 实例状态
docker exec -it batch-postgres-primary psql -U batch_user -d batch_platform -c \
  "select tenant_id, job_code, status, count(*) from batch.job_instance \
   where create_time > now() - interval '15 min' group by 1,2,3 order by 1,2"
```

## 跟 load-tests/(Gatling)的关系

- `load-tests/`:**性能 SLO gate**(p95 / p99 + 错误率断言),CI 跑
- `scripts/sim/`:**日常 dev 联调**(覆盖 IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC 五类 Worker 的业务场景与 Workflow 编排),本地跑

两者用途不同,不重叠。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [00-reset-runtime.sh](00-reset-runtime.sh) | reset-runtime.sh:把 sim 运行态数据清回干净基线,让整套 08→25 + 05/06/07 可重复跑。 |
| [01-init-biz.sh](01-init-biz.sh) | 初始化 SIM 业务表、权限与对象存储空间。 |
| [02-start-sim.sh](02-start-sim.sh) | start-sim.sh:起模拟下游容器(sftp + mockserver)。 |
| [03-import-tenants.sh](03-import-tenants.sh) | import-tenants.sh:把 ta/tb/tc 的 4 类 worker 配置导入到 console-api。 |
| [fix-fixture-xlsx.py](fix-fixture-xlsx.py) | 只读校验或修复四份 SIM 租户配置包；`--check` 不写工作簿，省略该参数会修复工作簿。 |
| [prepare-tenant-fixture.py](prepare-tenant-fixture.py) | 在导入私有副本中归一化已知 SIM API 渠道，保持原工作簿不变。 |
| [04-seed-source-data.sh](04-seed-source-data.sh) | seed-source-data.sh:把假业务文件投放到 SFTP 入站目录。 |
| [05-load.sh](05-load.sh) | 按导入完成顺序驱动多租户 SIM 主链负载。 |
| [06-sdk-worker-verify.sh](06-sdk-worker-verify.sh) | sdk-worker-verify.sh — 自托管 SDK worker 端到端真实调用验证(可重复执行)。 |
| [06-verify.sh](06-verify.sh) | 对账多租户 SIM 主链生成的业务数据与文件产物。 |
| [07-atomic-load.sh](07-atomic-load.sh) | 执行 Atomic 模拟负载并核对任务终态。 |
| [08-import-stage2.sh](08-import-stage2.sh) | import-stage2.sh:Import 业务分支系统级验证。 |
| [09-export-stage3.sh](09-export-stage3.sh) | export-stage3.sh:Export 业务分支系统级验证。 |
| [10-process-stage4.sh](10-process-stage4.sh) | process-stage4.sh:Process 业务分支系统级验证。 |
| [11-import-stage2b.sh](11-import-stage2b.sh) | import-stage2b.sh:Import LOAD/幂等/分片保护业务分支验证。 |
| [12-export-stage3b.sh](12-export-stage3b.sh) | Export 四分片正常路径及 MinIO 产物业务键无重无漏验证。 |
| [export_partition_evidence.py](export_partition_evidence.py) | 对 Stage 3b/3c 的 JSON 产物核对租户和跨分片业务键，拒绝重复或遗漏。 |
| [13-process-stage4b.sh](13-process-stage4b.sh) | process-stage4b.sh:Process 幂等重跑/失败恢复业务分支验证。 |
| [14-dispatch-stage5b.sh](14-dispatch-stage5b.sh) | dispatch-stage5b.sh:Dispatch 未知外部结果的自动重试围栏验证。 |
| [15-trigger-stage6b.sh](15-trigger-stage6b.sh) | trigger-stage6b.sh:Trigger 去重 + 小规模 storm 验证。 |
| [16-atomic-stage5b.sh](16-atomic-stage5b.sh) | atomic-stage5b.sh:Atomic shell/sql/stored-proc 成功态验证。 |
| [17-import-stage2c.sh](17-import-stage2c.sh) | import-stage2c.sh:Import BATCH_UPSERT/REPLACE 小矩阵验证。 |
| [18-export-stage3c.sh](18-export-stage3c.sh) | Export 八分片产物业务键、多租户与请求幂等验证。 |
| [19-process-stage4c.sh](19-process-stage4c.sh) | process-stage4c.sh:Process 分片 + 取消验证。 |
| [20-dispatch-stage5c.sh](20-dispatch-stage5c.sh) | dispatch-stage5c.sh:Dispatch LOCAL / NAS stub / SFTP manifest 验证。 |
| [21-atomic-stage5c.sh](21-atomic-stage5c.sh) | atomic-stage5c.sh:Atomic SSRF rejection / timeout / cancel 验证。 |
| [22-trigger-stage6c.sh](22-trigger-stage6c.sh) | trigger-stage6c.sh:Trigger scheduled / misfire / replay / storm 验证。 |
| [23-import-stage2d.sh](23-import-stage2d.sh) | import-stage2d.sh:Import bad-record skip 阈值系统级验证。 |
| [24-trigger-stage6d.sh](24-trigger-stage6d.sh) | 验证 Trigger 的调度、重试、暂停与恢复场景。 |
| [25-import-stage2e-checkpoint-crash.sh](25-import-stage2e-checkpoint-crash.sh) | Import checkpoint 真实崩溃续跑故障注入场景:。 |
| [26-bundle-import.sh](26-bundle-import.sh) | bundle-import.sh:ADR-046 文件束导入「全链」sim 阶段。 |
| [27-batch-claim-consume.sh](27-batch-claim-consume.sh) | batch-claim-consume.sh:ADR-046 P2 worker 消费端攒批 sim 回归。 |
| [28-import-mainline.sh](28-import-mainline.sh) | import-mainline.sh:三租户 IMPORT 主线系统级验证。 |
| [29-tenant-config-scenarios.sh](29-tenant-config-scenarios.sh) | tenant-config-scenarios.sh:租户场景模板真实 API / Excel 预览矩阵。 |
| [30-tenant-config-runtime-edit.sh](30-tenant-config-runtime-edit.sh) | tenant-config-runtime-edit.sh:运行中更新作业配置的实例边界 SIM。 |
| [98-quiesce-schedules.sh](98-quiesce-schedules.sh) | 静默 SIM 调度并等待在途任务收敛，供严格数据对账。 |
| [99-stop.sh](99-stop.sh) | 停模拟器容器(sftp + mockserver),保留 volume 让下次启动数据延续。 |
| [dr-drill-fleet-crash.sh](dr-drill-fleet-crash.sh) | DR 演练:worker 全 worker 组崩溃 → lease/task 超时回收 + 重投 → 重启 → 断言「精确一次」。 |
| [dr-drill-pitr.sh](dr-drill-pitr.sh) | DR 演练:PITR(时间点恢复)—— 验证 RPO(已提交数据不丢)+ RTO(恢复耗时)。 |
| [env-common.sh](env-common.sh) | sim 脚本公共变量。调用方可先设置 SIM_STAGE_NAME 覆盖 run 前缀。 |
| [env-lan.sh](env-lan.sh) | LAN(局域网)访问时 source 这个,让 sim 脚本走 LAN IP。 |
| [launch_contract.py](launch_contract.py) | 提供 SIM 的 Workflow 根触发协议辅助能力。 |
| [load_orchestration.py](load_orchestration.py) | 控制 SIM 导入完成后再触发下游导出与分发的顺序。 |
| [minio_listing.py](minio_listing.py) | 校验 SIM 导出场景中 MinIO JSON 列表的记录。 |
| [sql/atomic-load-failed-count.sql](sql/atomic-load-failed-count.sql) | 供 [07-atomic-load.sh](07-atomic-load.sh) 使用的配套 SQL。 |
| [sql/atomic-load-non-terminal-count.sql](sql/atomic-load-non-terminal-count.sql) | 供 [07-atomic-load.sh](07-atomic-load.sh) 使用的配套 SQL。 |
| [sql/atomic-load-summary.sql](sql/atomic-load-summary.sql) | 供 [07-atomic-load.sh](07-atomic-load.sh) 使用的配套 SQL。 |
| [sql/checkpoint-request-state.sql](sql/checkpoint-request-state.sql) | 供 [25-import-stage2e-checkpoint-crash.sh](25-import-stage2e-checkpoint-crash.sh) 使用的配套 SQL。 |
| [sql/cleanup-process-stage4b-stale-generation.sql](sql/cleanup-process-stage4b-stale-generation.sql) | DANGER: 删除 SIM 业务库内指定 tenant_id + batch_key 的 OPEN、未绑定 job_task 的 |
| [sql/cleanup-sim-output-files.sql](sql/cleanup-sim-output-files.sql) | DANGER: 删除指定 sim 批次生成的文件记录。仅限本地 sim 库，必须提供租户、作业和 batchNo 条件。 |
| [sql/count-bundle-import-customers.sql](sql/count-bundle-import-customers.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/count-checkpoint-customer-rows.sql](sql/count-checkpoint-customer-rows.sql) | 供 [25-import-stage2e-checkpoint-crash.sh](25-import-stage2e-checkpoint-crash.sh) 使用的配套 SQL。 |
| [sql/count-enabled-file-channel.sql](sql/count-enabled-file-channel.sql) | 供 [14-dispatch-stage5b.sh](14-dispatch-stage5b.sh) 使用的配套 SQL。 |
| [sql/count-enabled-job-definition.sql](sql/count-enabled-job-definition.sql) | 供 [13-process-stage4b.sh](13-process-stage4b.sh) 使用的配套 SQL。 |
| [sql/count-export-stage3-mismatches.sql](sql/count-export-stage3-mismatches.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/count-export-stage3-terminal-template.sql](sql/count-export-stage3-terminal-template.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/count-export-stage3-terminal.sql](sql/count-export-stage3-terminal.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/count-export-stage3c-success.sql](sql/count-export-stage3c-success.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/count-export-stage3c-tenant-files.sql](sql/count-export-stage3c-tenant-files.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/count-export-stage3c-terminal.sql](sql/count-export-stage3c-terminal.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/count-process-stage4-mismatches.sql](sql/count-process-stage4-mismatches.sql) | 供 [10-process-stage4.sh](10-process-stage4.sh) 使用的配套 SQL。 |
| [sql/count-process-stage4-terminal.sql](sql/count-process-stage4-terminal.sql) | 供 [10-process-stage4.sh](10-process-stage4.sh) 使用的配套 SQL。 |
| [sql/count-process-stage4b-staging.sql](sql/count-process-stage4b-staging.sql) | 供 [13-process-stage4b.sh](13-process-stage4b.sh) 使用的配套 SQL。 |
| [sql/count-sim-customer-account.sql](sql/count-sim-customer-account.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/count-sim-export-definitions.sql](sql/count-sim-export-definitions.sql) | 供 [06-verify.sh](06-verify.sh) 使用的配套 SQL。 |
| [sql/count-sim-export-ready-failures.sql](sql/count-sim-export-ready-failures.sql) | 供 [06-verify.sh](06-verify.sh) 使用的配套 SQL。 |
| [sql/count-sim-outbox-backlog.sql](sql/count-sim-outbox-backlog.sql) | 供 [06-verify.sh](06-verify.sh) 使用的配套 SQL。 |
| [sql/count-sim-risk-score.sql](sql/count-sim-risk-score.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/count-sim-transaction.sql](sql/count-sim-transaction.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/count-stage2c-appended-rows.sql](sql/count-stage2c-appended-rows.sql) | 供 [17-import-stage2c.sh](17-import-stage2c.sh) 使用的配套 SQL。 |
| [sql/count-stage2d-blocked-customers.sql](sql/count-stage2d-blocked-customers.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/count-stage2d-loaded-customer.sql](sql/count-stage2d-loaded-customer.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/count-successful-atomic-requests.sql](sql/count-successful-atomic-requests.sql) | 供 [16-atomic-stage5b.sh](16-atomic-stage5b.sh) 使用的配套 SQL。 |
| [sql/count-successful-job-instances.sql](sql/count-successful-job-instances.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/count-successful-request-instances.sql](sql/count-successful-request-instances.sql) | 供 [20-dispatch-stage5c.sh](20-dispatch-stage5c.sh) 使用的配套 SQL。 |
| [sql/count-terminal-import-instances.sql](sql/count-terminal-import-instances.sql) | 供 [08-import-stage2.sh](08-import-stage2.sh) 使用的配套 SQL。 |
| [sql/count-terminal-job-instances.sql](sql/count-terminal-job-instances.sql) | 供 [15-trigger-stage6b.sh](15-trigger-stage6b.sh) 使用的配套 SQL。 |
| [sql/count-terminal-request-instances.sql](sql/count-terminal-request-instances.sql) | 供 [16-atomic-stage5b.sh](16-atomic-stage5b.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6c-misfire-success.sql](sql/count-trigger-stage6c-misfire-success.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/count-trigger-stage6c-pending.sql](sql/count-trigger-stage6c-pending.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/count-trigger-stage6c-quartz-misfire.sql](sql/count-trigger-stage6c-quartz-misfire.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6c-scheduled.sql](sql/count-trigger-stage6c-scheduled.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6c-storm-success.sql](sql/count-trigger-stage6c-storm-success.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6c-storm-terminal.sql](sql/count-trigger-stage6c-storm-terminal.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-dedup-instances.sql](sql/count-trigger-stage6d-dedup-instances.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-dedup-rows.sql](sql/count-trigger-stage6d-dedup-rows.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-non-terminal.sql](sql/count-trigger-stage6d-non-terminal.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-outbox-instances.sql](sql/count-trigger-stage6d-outbox-instances.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-outbox-published.sql](sql/count-trigger-stage6d-outbox-published.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-outbox.sql](sql/count-trigger-stage6d-outbox.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-pending-outbox.sql](sql/count-trigger-stage6d-pending-outbox.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-pending.sql](sql/count-trigger-stage6d-pending.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/count-trigger-stage6d-storm-terminal.sql](sql/count-trigger-stage6d-storm-terminal.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/create-sim-tenant-fixture-import-state.sql](sql/create-sim-tenant-fixture-import-state.sql) | 供 [03-import-tenants.sh](03-import-tenants.sh) 使用的配套 SQL。 |
| [sql/disable-trigger-stage6c-jobs.sql](sql/disable-trigger-stage6c-jobs.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/dr-count-duplicate-instances.sql](sql/dr-count-duplicate-instances.sql) | 供 [dr-drill-pitr.sh](dr-drill-pitr.sh) 使用的配套 SQL。 |
| [sql/dr-count-inflight-tasks.sql](sql/dr-count-inflight-tasks.sql) | 供 [dr-drill-fleet-crash.sh](dr-drill-fleet-crash.sh) 使用的配套 SQL。 |
| [sql/dr-count-instances-after-time.sql](sql/dr-count-instances-after-time.sql) | 供 [dr-drill-pitr.sh](dr-drill-pitr.sh) 使用的配套 SQL。 |
| [sql/dr-count-instances-through-time.sql](sql/dr-count-instances-through-time.sql) | 供 [dr-drill-pitr.sh](dr-drill-pitr.sh) 使用的配套 SQL。 |
| [sql/dr-find-duplicate-instances.sql](sql/dr-find-duplicate-instances.sql) | 供 [dr-drill-fleet-crash.sh](dr-drill-fleet-crash.sh) 使用的配套 SQL。 |
| [sql/dr-find-duplicate-outbox.sql](sql/dr-find-duplicate-outbox.sql) | 供 [dr-drill-fleet-crash.sh](dr-drill-fleet-crash.sh) 使用的配套 SQL。 |
| [sql/dr-fingerprint-instances-through-time.sql](sql/dr-fingerprint-instances-through-time.sql) | 供 [dr-drill-pitr.sh](dr-drill-pitr.sh) 使用的配套 SQL。 |
| [sql/dr-task-status-distribution.sql](sql/dr-task-status-distribution.sql) | 供 [dr-drill-fleet-crash.sh](dr-drill-fleet-crash.sh) 使用的配套 SQL。 |
| [sql/has-sim-tenant-package-data.sql](sql/has-sim-tenant-package-data.sql) | 供 [03-import-tenants.sh](03-import-tenants.sh) 使用的配套 SQL。 |
| [sql/insert-bundle-generated-file.sql](sql/insert-bundle-generated-file.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/prepare-sftp-probe-fixtures.sql](sql/prepare-sftp-probe-fixtures.sql) | 新库配置包直接提供规范字段；这里只覆盖当前运行环境的路由与公钥路径。 |
| [sql/quiesce-schedules.sql](sql/quiesce-schedules.sql) | DANGER: 删除/停用 sim 调度夹具。仅限本地 sim 平台库，执行前确认 schedule 范围。 |
| [sql/record-sim-tenant-fixture-import.sql](sql/record-sim-tenant-fixture-import.sql) | 供 [03-import-tenants.sh](03-import-tenants.sh) 使用的配套 SQL。 |
| [sql/reset-business-runtime.sql](sql/reset-business-runtime.sql) | 供 [00-reset-runtime.sh](00-reset-runtime.sh) 使用的配套 SQL。 |
| [sql/restore-checkpoint-template-chunk-size.sql](sql/restore-checkpoint-template-chunk-size.sql) | 只恢复本场景写入的慢速值；并发写入其它值时不覆盖，由调用方报告冲突。 |
| [sql/select-atomic-assertion-summary.sql](sql/select-atomic-assertion-summary.sql) | 供 [21-atomic-stage5c.sh](21-atomic-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-atomic-request-status-details.sql](sql/select-atomic-request-status-details.sql) | 供 [21-atomic-stage5c.sh](21-atomic-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-atomic-request-task-status.sql](sql/select-atomic-request-task-status.sql) | 供 [16-atomic-stage5b.sh](16-atomic-stage5b.sh) 使用的配套 SQL。 |
| [sql/select-bundle-instance-after.sql](sql/select-bundle-instance-after.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/select-bundle-max-instance-id.sql](sql/select-bundle-max-instance-id.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/select-bundle-partition-terminal-summary.sql](sql/select-bundle-partition-terminal-summary.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/select-bundle-partitions.sql](sql/select-bundle-partitions.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/select-checkpoint-template-chunk-size.sql](sql/select-checkpoint-template-chunk-size.sql) | 供 [25-import-stage2e-checkpoint-crash.sh](25-import-stage2e-checkpoint-crash.sh) 使用的配套 SQL。 |
| [sql/select-current-timestamp.sql](sql/select-current-timestamp.sql) | 供 [dr-drill-pitr.sh](dr-drill-pitr.sh) 使用的配套 SQL。 |
| [sql/select-dispatch-failure-details.sql](sql/select-dispatch-failure-details.sql) | 供 [14-dispatch-stage5b.sh](14-dispatch-stage5b.sh) 使用的配套 SQL。 |
| [sql/select-dispatch-failure-message.sql](sql/select-dispatch-failure-message.sql) | 供 [14-dispatch-stage5b.sh](14-dispatch-stage5b.sh) 使用的配套 SQL。 |
| [sql/select-dispatch-failure-summary.sql](sql/select-dispatch-failure-summary.sql) | 供 [14-dispatch-stage5b.sh](14-dispatch-stage5b.sh) 使用的配套 SQL。 |
| [sql/select-dispatch-record-details.sql](sql/select-dispatch-record-details.sql) | 供 [20-dispatch-stage5c.sh](20-dispatch-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-dispatch-status-summary.sql](sql/select-dispatch-status-summary.sql) | 供 [20-dispatch-stage5c.sh](20-dispatch-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-export-file-records.sql](sql/select-export-file-records.sql) | 供 [12-export-stage3b.sh](12-export-stage3b.sh) 使用的配套 SQL。 |
| [sql/select-export-partition-task-status.sql](sql/select-export-partition-task-status.sql) | 供 [12-export-stage3b.sh](12-export-stage3b.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3-expectations.sql](sql/select-export-stage3-expectations.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3-file-status.sql](sql/select-export-stage3-file-status.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3-job-status.sql](sql/select-export-stage3-job-status.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3-object-sample.sql](sql/select-export-stage3-object-sample.sql) | 供 [09-export-stage3.sh](09-export-stage3.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3b-assertion.sql](sql/select-export-stage3b-assertion.sql) | 供 [12-export-stage3b.sh](12-export-stage3b.sh) 使用的配套 SQL。 |
| [sql/select-export-partition-objects.sql](sql/select-export-partition-objects.sql) | 供 Stage 3b/3c 获取本次导出对象路径，读取产物并核对完整性。 |
| [sql/select-export-stage3c-dedup-check.sql](sql/select-export-stage3c-dedup-check.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3c-file-records.sql](sql/select-export-stage3c-file-records.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3c-instance.sql](sql/select-export-stage3c-instance.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3c-job-status.sql](sql/select-export-stage3c-job-status.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3c-partition-status.sql](sql/select-export-stage3c-partition-status.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/select-export-stage3c-shard-check.sql](sql/select-export-stage3c-shard-check.sql) | 供 [18-export-stage3c.sh](18-export-stage3c.sh) 使用的配套 SQL。 |
| [sql/select-file-dispatch-status.sql](sql/select-file-dispatch-status.sql) | 供 [05-load.sh](05-load.sh) 使用的配套 SQL。 |
| [sql/select-import-customer-assertion.sql](sql/select-import-customer-assertion.sql) | 供 [11-import-stage2b.sh](11-import-stage2b.sh) 使用的配套 SQL。 |
| [sql/select-import-customer-details.sql](sql/select-import-customer-details.sql) | 供 [11-import-stage2b.sh](11-import-stage2b.sh) 使用的配套 SQL。 |
| [sql/select-import-file-status.sql](sql/select-import-file-status.sql) | 供 [08-import-stage2.sh](08-import-stage2.sh) 使用的配套 SQL。 |
| [sql/select-import-instance-status.sql](sql/select-import-instance-status.sql) | 供 [08-import-stage2.sh](08-import-stage2.sh) 使用的配套 SQL。 |
| [sql/select-import-mainline-business-counts.sql](sql/select-import-mainline-business-counts.sql) | 供 [28-import-mainline.sh](28-import-mainline.sh) 使用的配套 SQL。 |
| [sql/select-import-mainline-instance-status.sql](sql/select-import-mainline-instance-status.sql) | 供 [28-import-mainline.sh](28-import-mainline.sh) 使用的配套 SQL。 |
| [sql/select-import-mainline-task-status.sql](sql/select-import-mainline-task-status.sql) | 供 [28-import-mainline.sh](28-import-mainline.sh) 使用的配套 SQL。 |
| [sql/select-instance-partition-counts.sql](sql/select-instance-partition-counts.sql) | 供 [27-batch-claim-consume.sh](27-batch-claim-consume.sh) 使用的配套 SQL。 |
| [sql/select-job-instance-status-counts.sql](sql/select-job-instance-status-counts.sql) | 供 [15-trigger-stage6b.sh](15-trigger-stage6b.sh) 使用的配套 SQL。 |
| [sql/select-latest-sim-output-file.sql](sql/select-latest-sim-output-file.sql) | 供 [05-load.sh](05-load.sh) 使用的配套 SQL。 |
| [sql/select-one.sql](sql/select-one.sql) | 供 [dr-drill-pitr.sh](dr-drill-pitr.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4-expectations.sql](sql/select-process-stage4-expectations.sql) | 供 [10-process-stage4.sh](10-process-stage4.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4-job-status.sql](sql/select-process-stage4-job-status.sql) | 供 [10-process-stage4.sh](10-process-stage4.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4-staging-leftover.sql](sql/select-process-stage4-staging-leftover.sql) | 供 [10-process-stage4.sh](10-process-stage4.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4-target-rows.sql](sql/select-process-stage4-target-rows.sql) | 供 [10-process-stage4.sh](10-process-stage4.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4b-assertion.sql](sql/select-process-stage4b-assertion.sql) | 供 [13-process-stage4b.sh](13-process-stage4b.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4b-target.sql](sql/select-process-stage4b-target.sql) | 供 [13-process-stage4b.sh](13-process-stage4b.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-cancel-status-value.sql](sql/select-process-stage4c-cancel-status-value.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-cancel-status.sql](sql/select-process-stage4c-cancel-status.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-cancel-task.sql](sql/select-process-stage4c-cancel-task.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-instance.sql](sql/select-process-stage4c-instance.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-target-check.sql](sql/select-process-stage4c-target-check.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-target-summary.sql](sql/select-process-stage4c-target-summary.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-task-check.sql](sql/select-process-stage4c-task-check.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-process-stage4c-task-status.sql](sql/select-process-stage4c-task-status.sql) | 供 [19-process-stage4c.sh](19-process-stage4c.sh) 使用的配套 SQL。 |
| [sql/select-request-instance-status-only.sql](sql/select-request-instance-status-only.sql) | 供 [05-load.sh](05-load.sh) 使用的配套 SQL。 |
| [sql/select-request-instance-status.sql](sql/select-request-instance-status.sql) | 供 [21-atomic-stage5c.sh](21-atomic-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-request-job-diagnostics.sql](sql/select-request-job-diagnostics.sql) | 供 [17-import-stage2c.sh](17-import-stage2c.sh) 使用的配套 SQL。 |
| [sql/select-request-job-status.sql](sql/select-request-job-status.sql) | 供 [11-import-stage2b.sh](11-import-stage2b.sh) 使用的配套 SQL。 |
| [sql/select-request-task-assignment.sql](sql/select-request-task-assignment.sql) | 供 [06-sdk-worker-verify.sh](06-sdk-worker-verify.sh) 使用的配套 SQL。 |
| [sql/select-request-task-status-details.sql](sql/select-request-task-status-details.sql) | 供 [20-dispatch-stage5c.sh](20-dispatch-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-running-atomic-task.sql](sql/select-running-atomic-task.sql) | 供 [21-atomic-stage5c.sh](21-atomic-stage5c.sh) 使用的配套 SQL。 |
| [sql/select-sample-task-types.sql](sql/select-sample-task-types.sql) | 供 [06-sdk-worker-verify.sh](06-sdk-worker-verify.sh) 使用的配套 SQL。 |
| [sql/select-sim-dispatch-verification.sql](sql/select-sim-dispatch-verification.sql) | 供 [06-verify.sh](06-verify.sh) 使用的配套 SQL。 |
| [sql/select-sim-job-definition-id.sql](sql/select-sim-job-definition-id.sql) | 供 [30-tenant-config-runtime-edit.sh](30-tenant-config-runtime-edit.sh) 使用的配套 SQL。 |
| [sql/select-sim-recent-instance-statuses.sql](sql/select-sim-recent-instance-statuses.sql) | 供 [06-verify.sh](06-verify.sh) 使用的配套 SQL。 |
| [sql/select-sim-running-task-id.sql](sql/select-sim-running-task-id.sql) | 供 [30-tenant-config-runtime-edit.sh](30-tenant-config-runtime-edit.sh) 使用的配套 SQL。 |
| [sql/select-sim-runtime-config-observation.sql](sql/select-sim-runtime-config-observation.sql) | 供 [30-tenant-config-runtime-edit.sh](30-tenant-config-runtime-edit.sh) 使用的配套 SQL。 |
| [sql/select-sim-tenant-fixture-import.sql](sql/select-sim-tenant-fixture-import.sql) | 供 [03-import-tenants.sh](03-import-tenants.sh) 使用的配套 SQL。 |
| [sql/select-single-job-workflow-id.sql](sql/select-single-job-workflow-id.sql) | 供 [05-load.sh](05-load.sh) 使用的配套 SQL。 |
| [sql/select-stage2-import-business-counts.sql](sql/select-stage2-import-business-counts.sql) | 供 [08-import-stage2.sh](08-import-stage2.sh) 使用的配套 SQL。 |
| [sql/select-stage2-workflow-outcomes.sql](sql/select-stage2-workflow-outcomes.sql) | 供 [08-import-stage2.sh](08-import-stage2.sh) 使用的配套 SQL。 |
| [sql/select-stage2b-import-status.sql](sql/select-stage2b-import-status.sql) | 供 [11-import-stage2b.sh](11-import-stage2b.sh) 使用的配套 SQL。 |
| [sql/select-stage2c-import-rows.sql](sql/select-stage2c-import-rows.sql) | 供 [17-import-stage2c.sh](17-import-stage2c.sh) 使用的配套 SQL。 |
| [sql/select-stage2c-import-status.sql](sql/select-stage2c-import-status.sql) | 供 [17-import-stage2c.sh](17-import-stage2c.sh) 使用的配套 SQL。 |
| [sql/select-stage2c-replace-assertion.sql](sql/select-stage2c-replace-assertion.sql) | 供 [17-import-stage2c.sh](17-import-stage2c.sh) 使用的配套 SQL。 |
| [sql/select-stage2c-upsert-assertion.sql](sql/select-stage2c-upsert-assertion.sql) | 供 [17-import-stage2c.sh](17-import-stage2c.sh) 使用的配套 SQL。 |
| [sql/select-stage2d-error-records.sql](sql/select-stage2d-error-records.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/select-stage2d-error-summary.sql](sql/select-stage2d-error-summary.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/select-stage2d-file-records.sql](sql/select-stage2d-file-records.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/select-stage2d-job-status.sql](sql/select-stage2d-job-status.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/select-stage2d-request-status.sql](sql/select-stage2d-request-status.sql) | 供 [23-import-stage2d.sh](23-import-stage2d.sh) 使用的配套 SQL。 |
| [sql/select-stage5b-channel-health.sql](sql/select-stage5b-channel-health.sql) | 供 [14-dispatch-stage5b.sh](14-dispatch-stage5b.sh) 使用的配套 SQL。 |
| [sql/select-stage6b-workflow-outcomes.sql](sql/select-stage6b-workflow-outcomes.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/select-trigger-request-dedup-counts.sql](sql/select-trigger-request-dedup-counts.sql) | 供 [15-trigger-stage6b.sh](15-trigger-stage6b.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6c-pending-id.sql](sql/select-trigger-stage6c-pending-id.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-trigger-stage6c-replay-status.sql](sql/select-trigger-stage6c-replay-status.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6c-status.sql](sql/select-trigger-stage6c-status.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6d-outbox-status.sql](sql/select-trigger-stage6d-outbox-status.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6d-pending-link.sql](sql/select-trigger-stage6d-pending-link.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6d-pending-snapshot.sql](sql/select-trigger-stage6d-pending-snapshot.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6d-planned-misfire.sql](sql/select-trigger-stage6d-planned-misfire.sql) | 仅在 occurrence 规划器完成持久化后，读取 Quartz 实际的下一次触发时间。 |
| [sql/select-trigger-stage6d-recovery-outcome.sql](sql/select-trigger-stage6d-recovery-outcome.sql) | 供 [22-trigger-stage6c.sh](22-trigger-stage6c.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6d-replay-status.sql](sql/select-trigger-stage6d-replay-status.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/select-trigger-stage6d-request-status.sql](sql/select-trigger-stage6d-request-status.sql) | 供 [24-trigger-stage6d.sh](24-trigger-stage6d.sh) 使用的配套 SQL。 |
| [sql/select-worker-capabilities.sql](sql/select-worker-capabilities.sql) | 供 [06-sdk-worker-verify.sh](06-sdk-worker-verify.sh) 使用的配套 SQL。 |
| [sql/select-worker-status.sql](sql/select-worker-status.sql) | 供 [06-sdk-worker-verify.sh](06-sdk-worker-verify.sh) 使用的配套 SQL。 |
| [sql/select-workflow-code-by-id.sql](sql/select-workflow-code-by-id.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/select-workflow-definition-id.sql](sql/select-workflow-definition-id.sql) | 供 [05-load.sh](05-load.sh) 使用的配套 SQL。 |
| [sql/update-trigger-stage6c-quartz-misfire.sql](sql/update-trigger-stage6c-quartz-misfire.sql) | 多租户 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/upsert-bundle-export-customers.sql](sql/upsert-bundle-export-customers.sql) | 供 [26-bundle-import.sh](26-bundle-import.sh) 使用的配套 SQL。 |
| [sql/verify-sim-tenant-fixture-import.sql](sql/verify-sim-tenant-fixture-import.sql) | 供 [03-import-tenants.sh](03-import-tenants.sh) 使用的配套 SQL。 |
