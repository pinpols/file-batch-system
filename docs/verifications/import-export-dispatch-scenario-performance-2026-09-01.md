# Import / Export / Dispatch 场景与性能验证报告

## 1. 范围与环境

验证日期：2026-09-01。

本轮使用 Docker 基础设施和本地启动的 JVM 后端，没有另造测试环境：

- PostgreSQL 17：`batch-postgres-primary` / `batch-postgres-replica`
- Kafka：`batch-kafka`，容器内查询地址为 `kafka:29092`
- MinIO：`batch-minio`
- Valkey：`batch-valkey`
- SFTP、MockServer：用于分发场景
- Console、Trigger、Orchestrator、Import、Export、Dispatch、Process、Atomic 健康检查均为 `UP`

本报告关注 Import、Export、Dispatch。Process 仅作为现有 load script 的伴随基线，不作为本轮专项结论。

## 2. 场景覆盖

| 领域 | 场景 | 结果 | 验证入口 |
|---|---|---:|---|
| Import | bad record skip，低于/超过阈值 | PASS | `scripts/sim/23-import-stage2d.sh` |
| Import | XML、FIXED_WIDTH 成功与 malformed/short 失败 | PASS | `scripts/sim/08-import-stage2.sh` |
| Import | UPSERT 幂等、目标表 LOAD 失败、分区保护 | PASS | `scripts/sim/11-import-stage2b.sh` |
| Import | APPEND、UPSERT、PARTITION_REPLACE_COPY | PASS | `scripts/sim/17-import-stage2c.sh` |
| Import | checkpoint 中途 kill worker、lease 回收、同实例重试 | PASS | `scripts/sim/25-import-stage2e-checkpoint-crash.sh` |
| Export | JSON、FIXED_WIDTH、EXCEL、坏 SQL 失败 | PASS | `scripts/sim/09-export-stage3.sh` |
| Export | 4 分区导出，文件数和行数校验 | PASS | `scripts/sim/12-export-stage3b.sh` |
| Export | 8 分片 replay、多租户导出 | PASS | `scripts/sim/18-export-stage3c.sh` |
| Dispatch | API 500、无重试、失败后补偿终态 | PASS | `scripts/sim/14-dispatch-stage5b.sh` |
| Dispatch | LOCAL、NAS stub、SFTP、`.chk` sidecar | PASS | `scripts/sim/20-dispatch-stage5c.sh` |

上述场景覆盖了成功、格式错误、业务校验失败、幂等、分区模式、断点恢复、分片、重放、外部传输失败和 sidecar 校验等主要逻辑分支。

## 3. 端到端性能基线

入口：`load-tests/scripts/run-worker-load-tests.sh`。

该脚本按 Import、Export、Dispatch、Process 顺序运行，当前不是四类 worker 同时混压。运行使用真实 Console API、真实 PostgreSQL/Kafka/MinIO 链路，测试完成后按 run id 清理测试数据。

### 3.1 单请求稳定基线

运行标识：`perf-import-export-dispatch-20260901d`，`IMPORT_PROFILE=medium`，每类 worker 1 个用户。

| Job | 总数 | 成功 | 失败 | 非终态 | 平均耗时 | p95 |
|---|---:|---:|---:|---:|---:|---:|
| Export | 1 | 1 | 0 | 0 | 2.665 s | 2.665 s |
| Import | 1 | 1 | 0 | 0 | 2.176 s | 2.176 s |
| Dispatch | 1 | 1 | 0 | 0 | 0.874 s | 0.874 s |
| Process | 1 | 1 | 0 | 0 | 3.692 s | 3.692 s |

业务核对结果：Import 写入 1,000 行；Export 读取 5,000 行；Process 读取 5,000 行并写入 500 行；Dispatch 1 条记录、1 个文件。任务状态全部为 `SUCCESS`。

原始报告：`load-tests/target/worker-load-report-perf-import-export-dispatch-20260901d.md`。

### 3.2 三并发观察

运行标识：`perf-import-export-dispatch-20260901c`，每类 worker 3 个用户。

| Job | 总数 | 成功 | 失败 | 非终态 | 平均耗时 | p95 |
|---|---:|---:|---:|---:|---:|---:|
| Export | 3 | 3 | 0 | 0 | 1.512 s | 2.747 s |
| Import | 3 | 3 | 0 | 0 | 1.115 s | 1.746 s |
| Dispatch | 3 | 2 | 1 | 0 | 1.502 s | 2.629 s |
| Process | 3 | 3 | 0 | 0 | 1.633 s | 1.668 s |

Dispatch 的 1 个失败不是 HTTP launch 失败，而是 3 个并发任务复用同一文件/渠道时，ACK 状态 CAS 竞争，失败消息为 `failed to mark acked`。这说明当前 load fixture 不适合作为共享文件并发吞吐基线，同时暴露出同一 dispatch 资源并发确认时的业务语义边界。不能将该结果解读为 Dispatch 单请求链路整体不可用。

原始报告：`load-tests/target/worker-load-report-perf-import-export-dispatch-20260901c.md`。

### 3.3 Import COPY 微基准

脚本：`scripts/local/import-copy-worth-benchmark.sh`。测试表为专用临时业务表，不读写真实业务表。

| 数据量 | 索引 | 批量 UPSERT | COPY + merge | 直接 COPY 替换 |
|---:|---:|---:|---:|---:|
| 100,000 行 | 0 个额外索引 | 40,330 行/s | 39,263 行/s，0.97x | 65,332 行/s，1.62x |
| 100,000 行 | 3 个额外索引 | 28,926 行/s | 29,437 行/s，1.02x | 51,278 行/s，1.77x |

当前结论：COPY + merge 未达到既定 2x 门槛，不建议替换默认 UPSERT 路径；分区整批替换的直接 COPY 有明显收益，但本轮仍低于 2x 门槛，暂不默认切换。该结论只适用于当前本地 PostgreSQL、表结构和数据规模，生产参数矩阵需要另行复验。

### 3.4 真实对象存储大文件导入

对象：MinIO `batch-dev/ingress/ta/ta-customer-BIG-20260905.csv`，
74,177,868 bytes（约 71 MiB），800,000 条数据记录。触发入口仍为真实
`POST /api/triggers/launch`，参数只提供 `fileId/storageBucket/storagePath`，不携带内联
`content`，因此实际经过 `Receive → PREPROCESS spool → PARSE → VALIDATE → LOAD`。

首次复跑暴露了真实代码缺陷：`FormatParseRequest.openTextReader()` 在返回 Reader 前关闭了底层
InputStream，任务报 `IMPORT_PARSE_FAILED / ClosedChannelException`。已修复资源所有权，并增加
`FormatParseRequestTest`；修复后连续两次复跑均通过：

| Run | Instance / task | 结果 | 业务行数 | 文件元数据 |
|---|---|---|---:|---|
| `local-large-object-import-20260901-160707` | 732 / 874 | SUCCESS | 800,000 | 800,000 parsed/loaded |
| `local-large-object-import-fixed-20260901-161307` | 733 / 875 | SUCCESS | 800,000 | 800,000 parsed/loaded |

第二次复跑还验证了失败后重试成功时，`file_record.metadata_json` 会清除旧的
`errorCode/errorMessage/errorKey/errorArgs`，避免前台出现“成功但仍有错误”的矛盾状态。
原始对象保留，测试实例和业务测试行已清理。

### 3.5 Import / Export / Dispatch 阶梯压测

入口：`load-tests/scripts/run-worker-stress-tests.sh`，运行标识
`local-import-export-dispatch-stress-medium-20260901`，使用真实 Console/Trigger API 和
Docker PostgreSQL/Kafka/MinIO。每个阶梯仍按 Import、Export、Dispatch、Process 顺序执行，
不是同时混压；所有实例在等待窗口内收敛到终态。

| 并发用户 | Import | Export | Dispatch | Process |
|---:|---:|---:|---:|---:|
| 1 | 1/1，1.664 s | 1/1，2.246 s | 1/1，4.160 s | 1/1，2.832 s |
| 2 | 2/2，3.348 s | 2/2，3.038 s | 1/2，4.838 s | 2/2，1.345 s |
| 4 | 4/4，3.277 s | 4/4，11.616 s | 3/4，2.786 s | 4/4，1.421 s |
| 8 | 8/8，11.032 s | 8/8，3.693 s | 5/8，4.993 s | 6/8，3.102 s |

Dispatch 失败为共享 fixture 的同一 file/channel ACK CAS 竞争（`DISPATCH_ACK_FAILED`），
8 并发另有 1 个明确 `BUSINESS_ERROR`；Process 的 8 并发有 2 个业务失败。它们均已落到
明确终态，不能作为“全链路 8 并发成功”结论。Import 和 Export 在该阶梯全部成功。

原始报告：
`load-tests/target/worker-stress-report-local-import-export-dispatch-stress-medium-20260901.md`。

## 4. 运行态与资源信号

验证结束后的即时检查：

- 8 个本地后端健康检查均为 `UP`
- Import、Export、Dispatch、Process、Atomic 相关 Kafka consumer group 的 lag 均为 `0`
- `job_instance` 非终态：`0`
- `job_task` 非终态：`0`
- `outbox_event` 待发布：`0`
- `trigger_outbox_event` 待发布：`0`

Kafka lag 在容器内必须使用 `kafka:29092` 查询。容器内使用宿主机广播地址 `localhost:19092` 会失败，这是 Docker listener 地址选择导致的查询方式问题，不代表消费积压。

## 5. 已发现并修复的测试问题

本轮首次运行暴露了测试 fixture 问题，已在测试脚本/SQL中修复：

1. load fixture 缺少 Import/Export pipeline definition，导致 Import 报 `error.pipeline.definition_not_found`。
2. Export fixture 使用了旧 stage code，和 `DefaultExportStageExecutor` 的 `PREPARE/GENERATE/STORE/REGISTER/COMPLETE` 合约不一致。
3. Import 业务计数使用 trace 前缀过滤，无法按 run id 对账；已统一测试数据和报告查询前缀。
4. `MAX_ERROR_PCT=0.0` 与 Gatling 的严格阈值判断不兼容，即使 0 失败也会被判定为失败；运行基线改为 `0.1`。

这些是测试入口/fixture 问题，不是生产业务代码回归。Import 校验链路同时移除了逐记录 INFO 日志，避免大批量输入产生无效日志 I/O。

5. 真实对象导入首次暴露 `FormatParseRequest` 提前关闭流的问题，已修复并用真 MinIO
   71 MiB/800,000 行对象连续复跑通过；`LOADED` 成功回写同时清理旧失败元数据，并有真 PG
   mapper 回归测试。
6. inline `IMPORT_PROFILE=large` 的参数约 1.13 MiB，超过默认 Kafka 请求安全预算；压测入口
   已增加前置校验并提示改用对象存储导入，避免把 Kafka `RecordTooLargeException` 误判为 worker
   业务故障。

## 6. 未完成与边界

以下项目不能从本轮结果宣称已完成：

1. `30-gen-bigfiles.sh` 生成的约 71 MiB 对象已完成真实 worker 全量导入和业务对账；约 33 MiB、107 MiB 对象尚未在本轮完成全量导入对账。
2. 尚未完成 1,000 万行/接近 GiB 数据的真实 Import、Export 全链路性能证据。
3. load script 当前是四类 worker 逐类运行，尚未形成 Import + Export + Dispatch 同时运行的混压结果。
4. Export 的 8/16/32 分片高并发、真实外部 S3/OSS multipart abort/retry 尚未在本轮复验。
5. Dispatch 的真实生产 SFTP/NAS/API 故障注入和外部 ACK 延迟上限尚未替代本地 stub/LOCAL 基线。
6. 本轮未修改 Dispatch 的共享文件 ACK CAS 业务语义；如要测并发吞吐，应先让每个 VU 使用独立 file/channel，另设共享文件并发作为一致性专项。当前阶梯结果已明确记录该边界。

## 7. 结论

Import、Export、Dispatch 的主要业务场景和故障分支已通过现有 sim 验证；真实 MinIO 71 MiB/800,000 行对象导入已在修复后连续通过。本地 Docker 基础环境下的单请求性能基线全绿，Import/Export 阶梯至 8 并发全绿；Dispatch 共享资源竞争和 Process 高并发失败已如实记录。当前可以把 `USERS_PER_WORKER=1` 作为稳定回归基线，把阶梯结果作为容量边界观察。

本轮不能替代生产容量验收：真实大文件全链路、千万级数据、三类 worker 同时混压和外部对象存储/传输系统仍需单独取得证据。
