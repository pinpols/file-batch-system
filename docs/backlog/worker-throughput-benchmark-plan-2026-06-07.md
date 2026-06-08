# Worker 吞吐优化与 Benchmark 总控计划

> 状态:**P0/P1 已收口:import/export/process 完成 1000w 级 benchmark;dispatch/atomic/trigger 高压复验已消除 `CREATED + NO_TASK` 残留**。
> 日期:2026-06-07
> 范围:`batch-worker-import`,`batch-worker-export`,`batch-worker-process`,`batch-worker-dispatch`,`batch-worker-atomic`,`batch-trigger`,`batch-orchestrator`
> 关联:
> - [single-node-throughput-optimization-2026-06-06](./single-node-throughput-optimization-2026-06-06.md)
> - [citus-introduction-plan-2026-06-06](./citus-introduction-plan-2026-06-06.md)
> - [pre-production-capacity-optimization-plan-2026-06-08](./pre-production-capacity-optimization-plan-2026-06-08.md)

## 结论

1. **import/export P0/P1 已完成并系统复验**。导入覆盖 1000w `PARTITION_REPLACE_COPY` / 分片 guard / stage-swap；导出覆盖 1000w 单片、4 分片正确性、multipart/store 段、参数读取和 4 分片真并行。最终导出 trace `bb7343da2bd24313b8abbb99b8807c1f`,4 个 task 同秒 RUNNING,instance wall `144.092s`。
2. **process 已完成 1000w 大数据 benchmark 和 P1 收口**。聚合 1000w -> 10w 端到端 40.966s；旧 JSONB copy 867.606s；新增 `stagingMode=DIRECT` 后 1000w copy 端到端 62.978s,staging 残留 0。
3. **dispatch/atomic/trigger 已完成小基线 + 高压复验**。小基线全绿；修复后高压 RUN_ID `ctlw-202606080130-t1t2` 的 HTTP/Gatling 900/900 OK,540/540 实例全部进入终态,不再残留 `CREATED + NO_TASK`。
4. **本地高压失败是容量策略下的可观察终态,不是 worker 卡死**。本地 `tenant_quota_policy.exceeded_strategy=REJECT`、`dispatch_queue` 只有 3 job / 6 partition,高压下部分实例被终态化为 `FAILED/NO_TASK`;这是预期的背压结果。
5. **当前还不是 1w/10w 容量上限结论**。P0 状态机缺陷已修,下一步 task storm、故障注入、重试/背压上限可独立跑。
6. 所有 benchmark 都走正常系统链路:API / trigger / orchestrator / Kafka / worker / DB,不走前台模拟;代码完成状态和 benchmark 结果分开记录。

## 覆盖口径

本轮可以判断为 **5 类 worker 的 P0/P1 性能优化和主链路验证已收口**。这里的 5 类按执行域合并统计:

| 类别 | 覆盖的 worker | 本轮完成口径 |
|---|---|---|
| import | `batch-worker-import` | 1000w 级导入、replace-copy、stage-swap、分片 guard 和关键参数能力 |
| export | `batch-worker-export` | 1000w 级导出、4 分片真并行、multipart/store、fetch/chunk 参数读取 |
| process | `batch-worker-process` | 1000w aggregate/copy、SQL timeout / Kafka poll 配置、`DIRECT` fast path |
| dispatch / atomic | `batch-worker-dispatch`,`batch-worker-atomic` | 控制面小基线、高压复验、T1/T2 失败终态化、Kafka lag=0 |
| trigger | `batch-trigger` | API launch + scheduler read 并行压测、下游触发链路终态复验 |

但这 **不等于 worker 内部所有场景已经全量测完**。未覆盖或不作为本轮 P0/P1 阻塞的矩阵如下:

| 方向 | 未全量覆盖项 | 决策 |
|---|---|---|
| import | JVM 生产参数、PG WAL/checkpoint、`work_mem` / `maintenance_work_mem`、chunk/batch size 多组合 | 后续容量画像;当前主链路和配置能力已收口 |
| export | 更高分片数、真实 S3、多租户混压、不同格式大文件矩阵 | 后续容量画像;当前 4 分片真并行和 1GiB+ multipart 已收口 |
| process | 大数据失败恢复、幂等重跑、中途失败恢复、staging 分片/索引矩阵 | 后续 failure / capacity profile;当前 DIRECT 主收益已验证 |
| dispatch / atomic | 故障注入、失败重试风暴、下游异常、lease renew 极限、atomic executor 分类矩阵 | 后续稳定性 profile;当前高压下无 `CREATED + NO_TASK` 残留 |
| trigger | 高频 cron、misfire 补点风暴、Quartz scan/index 矩阵、1w/10w task storm | 后续容量上限 profile;当前 API launch + scheduler read P1 已收口 |

因此后续沟通统一用两个口径:

- **P0/P1 收口**:已完成,可作为本轮 worker 性能优化交付结论。
- **全场景矩阵**:未完成,按容量画像 / failure profile / 真实外部依赖 profile 继续排期。

## 业务场景 / 逻辑分支覆盖口径

本轮不是业务全分支验收。已覆盖的是每类 worker 的主业务链路和 P0/P1 高风险分支;未覆盖的是低频格式、外部依赖、失败恢复和组合矩阵。后续不能把“P0/P1 性能收口”等同于“业务逻辑分支全覆盖”。

| Worker | 已系统覆盖的业务分支 | 只做了单测 / 小基线的分支 | 未系统覆盖的业务分支 |
|---|---|---|---|
| import | 大文件对象导入、1000w `PARTITION_REPLACE_COPY`、1000w `PARTITION_STAGE_SWAP_COPY`、单大文件分片 fail-fast、DELIMITED 主路径、XML/FIXED_WIDTH 成功态、XML/FIXED_WIDTH 解析失败、字段校验失败、APPEND、UPSERT 重跑幂等、LOAD failure、分区 COPY 正向和多分片 guard、bad-record skip 阈值内/超阈值 | PG jsonb `field_mappings`、PG session 参数生成 | Excel multipart 配置包上传不属于 worker-import trigger 链路;checkpoint 崩溃续跑需 fault-injection profile |
| export | DELIMITED 1000w 单片、DELIMITED 1000w 4 分片正确性、4 分片真并行、MinIO multipart、JSON/FIXED_WIDTH/EXCEL 系统级小矩阵、bad SQL 失败态、keyset-range 4/8 分片小矩阵、requestId replay 幂等、多租户小混压 | `query_param_schema` / cursor / fetch 配置读取单测与模板复验 | 真实 S3、外部存储故障、不同格式的大文件矩阵、multipart abort/retry、导出失败恢复/重试/幂等 |
| process | SQL aggregate 1000w -> 10w、SQL copy 1000w、`stagingMode=DIRECT` fast path、SQL timeout / Kafka poll 配置、JSONB staging 小矩阵、DIRECT 小矩阵、validation failure、empty result SUCCESS、稳定 batchKey 幂等重跑、残留 staging 恢复、4 分片 SQL 参数小矩阵 | spec 校验、DIRECT 限制条件 | RUNNING cancel 当前返回 409、shell/SQL cooperative timeout 组合、staging 分区/索引矩阵、不同 process plugin 类型 |
| dispatch | local dispatch 小基线、高压下任务派发/claim/report 主路径、T1/T2 失败终态化、HTTP 成功分支、HTTP 500 retry/no-retry 补偿语义、no-retry `FAILED/COMPENSATED` 自动脚本、LOCAL/NAS/SFTP sidecar manifest | channel config merge、remote path 规则 | EMAIL/OSS 真实外部依赖端到端、断点重试、幂等投递、失败重试风暴 |
| atomic | SQL atomic direct 主路径、shell 成功、stored-proc 真实 PG 成功、小基线、高压下终态化、shell/sql/stored-proc 终态自动脚本、HTTP 非 loopback 真成功、SQL timeout 分类、task cancel 信号 | HTTP executor dry-run/安全闸单测 | shell cancel 不终止子进程、lease renew 极限、取消/超时/重试组合 |
| trigger | API launch、scheduler read 并行压测、trigger -> orchestrator -> worker 下游终态、requestId 去重系统级小矩阵、30/60 请求 API storm 收敛、scheduler 定时触发、misfire pending、replay approve | dedup 主路径观察 | pending->catch-up 自动关联缺设计修复；高频 cron、Quartz scan/index 压力、1w/10w task storm |

结论:

- **业务主路径**:本轮已覆盖到可以支撑 P0/P1 性能结论。
- **业务全分支**:没有完成;需要另开业务场景覆盖矩阵,按 worker 类型逐条补系统级用例。

## 本地模拟真实上下游业务矩阵计划

目标:在现有 `scripts/sim`、`scripts/sim-4day`、`load-tests`、`batch-e2e-tests` 基础上,扩展一轮本地模拟真实上下游的 worker 业务场景系统级验证。该计划不重造一套环境,优先复用已有 SFTP、MockServer、MinIO、PG、Kafka、多租户 ta/tb/tc fixture。

### 模拟边界

| 外部/上下游 | 本地模拟方式 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| SFTP | `scripts/sim/compose.yml` 的 SFTP 容器 | 入站/出站文件、权限路径、断点/失败分支 | 真实客户 SFTP 网络抖动、权限策略差异 |
| HTTP 下游 | MockServer stub | 2xx/4xx/5xx/timeout、幂等投递、重试语义 | 真实第三方 SLA、限流策略、证书链问题 |
| 对象存储 | MinIO | 大对象上传/下载、multipart、对象元数据 | 真实 S3 区域、IAM、跨公网延迟 |
| DB | 本地 PostgreSQL | 业务表、stored-proc、事务/锁/回滚语义 | 生产规格、真实 WAL/IO/HA 行为 |
| Kafka / Redis | 本地 docker compose | topic/consumer/lag/lease/背压主语义 | 生产多 broker、跨 AZ、真实 broker 故障 |
| 邮件 / OSS / NAS | 本地 stub 或 local path | 协议适配、失败/重试路径 | 真实供应商行为和网络条件 |

### 阶段计划

| 阶段 | 目标 | 主要动作 | 输出 |
|---|---|---|---|
| 0. 盘点与固化矩阵 | 避免重复造轮子 | 汇总 `scripts/sim`、`scripts/sim-4day`、`load-tests`、`batch-e2e-tests` 已有覆盖;形成 worker × 业务分支矩阵 | 更新本文档覆盖表 |
| 1. 复跑现有 sim 基线 | 证明现有模拟器基础不坏 | 跑 `01-init-biz.sh`、`02-start-sim.sh`、`03-import-tenants.sh`、`04-seed-source-data.sh`、`05-load.sh`、`06-verify.sh`、`07-atomic-load.sh` | 现有 sim 覆盖报告 |
| 2. Import 补齐 | 补 worker-import 业务分支 | 已完成 XML/FIXED_WIDTH、validation error、APPEND、UPSERT、LOAD failure、分区 COPY 正/负、bad-record skip 阈值；checkpoint 崩溃续跑需 fault injection | import 业务分支结果表 |
| 3. Export 补齐 | 补 worker-export 格式/存储/分片分支 | 已完成 DELIMITED/JSON/FIXED_WIDTH/EXCEL、单片/4/8 分片、keyset-range、bad SQL、requestId replay、多租户小混压；multipart abort/retry 和真实 S3 待故障注入 | export 业务分支结果表 |
| 4. Process 补齐 | 补 worker-process 计算/恢复分支 | 已完成 SQL aggregate、JSONB staging、`DIRECT`、validation、`emptyResultPolicy`、幂等重跑、staging cleanup、4 分片 process；RUNNING cancel/timeout 语义待设计修复 | process 业务分支结果表 |
| 5. Dispatch/Atomic 外部依赖模拟 | 补外部下游成功/失败/超时 | 已完成 Dispatch HTTP 成功/500 retry/no-retry/补偿、LOCAL/NAS/SFTP sidecar，Atomic SQL/stored-proc/shell/HTTP 真成功/timeout/cancel signal；EMAIL/OSS 和 cancel kill 待 profile/修复 | dispatch/atomic 业务分支结果表 |
| 6. Trigger 补齐 | 补调度类业务分支 | 已完成 API launch、requestId/dedup、30/60 storm、scheduler fire、misfire pending、replay approve；pending->catch-up 自动关联和高频 cron 待修/压测 | trigger 业务分支结果表 |
| 7. 统一自动化入口 | 降低复跑成本 | `load-tests/scripts/run-worker-business-scenario-matrix.sh` 默认 smoke 已覆盖 `2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c`;Stage 2d 需 skip profile 显式运行 | 一键 smoke/full |
| 8. 文档沉淀 | 形成验收口径 | 新增 `docs/verifications/worker-business-scenario-matrix-2026-06-08.md` 并回填本文档 | 最终业务矩阵报告 |

### 建议自动化入口

新增脚本:

```bash
load-tests/scripts/run-worker-business-scenario-matrix.sh
```

建议参数:

| 参数 | 示例 | 说明 |
|---|---|---|
| `MODULES` | `import,export,process,dispatch,atomic,trigger` | 指定要跑的 worker 集合 |
| `PROFILE` | `smoke` / `full` / `failure` | smoke 跑主分支;full 跑业务矩阵;failure 跑故障/超时/重试 |
| `TENANTS` | `ta,tb,tc` | sim 多租户集合 |
| `KEEP_DATA` | `1` | 保留数据便于排障 |
| `RUN_ID` | `biz-sim-20260608-001` | 报告和日志关联 id |

建议输出:

```text
load-tests/target/worker-business-scenario-report-<RUN_ID>.md
```

报告字段:

| 字段 | 说明 |
|---|---|
| worker | import/export/process/dispatch/atomic/trigger |
| scenario | 业务场景名 |
| upstream/downstream | 模拟的上下游依赖 |
| trigger path | API / cron / replay / direct |
| expected terminal | SUCCESS / FAILED / REJECTED / DLQ |
| actual terminal | 实际终态 |
| evidence | job_instance、job_task、file_record、MinIO/SFTP/MockServer 证据 |
| limitation | 本地模拟限制 |

### 优先级与周期

| 优先级 | 内容 | 预计 |
|---|---|---|
| P0 | 复跑现有 sim 基线 + import/export/process 主业务分支补齐 | 已完成 |
| P1 | dispatch/atomic 外部 mock failure + trigger cron/misfire smoke | 已完成本地 smoke；容量/故障注入另排 |
| P2 | full 业务矩阵、1k task storm、真实外部依赖替身扩展 | 后续容量画像 / 外部依赖 profile |

结论:这轮可以在本地做“模拟真实上下游”的系统级业务覆盖,但结论必须标注为本地模拟环境;真实 S3/邮件网关/客户 SFTP/NAS/第三方 HTTP SLA 仍需生产相似环境单独验收。

### 阶段 0 资产盘点(已确认可复用)

| 资产 | 路径 | 已有能力 | 可直接支撑的阶段 |
|---|---|---|---|
| 多租户业务模拟器 | `scripts/sim/` | ta/tb/tc 三租户;SFTP inbound;MockServer HTTP 下游;MinIO outbound;IMPORT/EXPORT/DISPATCH/WORKFLOW 触发与对账 | 阶段 1、2、3、5 |
| sim 容器编排 | `scripts/sim/compose.yml` | SFTP + MockServer,与主 compose 同 network | 阶段 1、5 |
| sim fixture 配置包 | `docs/test-data/test-full-coverage-import-suite/{ta,tb,tc}-tenant-config-package-test.xlsx` | 三租户 job/template/channel/workflow 配置 | 阶段 1、2、3、5 |
| sim 触发入口 | `scripts/sim/05-load.sh` | IMPORT 内联 CSV、EXPORT、DISPATCH、WORKFLOW 批量触发 | 阶段 1、2、3、5 |
| sim 对账入口 | `scripts/sim/06-verify.sh` | biz 表、MinIO、MockServer/SFTP 产物对账 | 阶段 1、8 |
| atomic sim 入口 | `scripts/sim/07-atomic-load.sh` | SQL / shell / stored-proc / HTTP 四类 atomic demo launch | 阶段 5 |
| 4 日/10 租户批量模拟 | `scripts/sim-4day/` | 10 租户、4 bizDate、IMPORT→EXPORT→DISPATCH→WORKFLOW、递增数据和大文件登记 | 阶段 3、6、容量画像 |
| 控制面压测入口 | `load-tests/scripts/run-control-plane-worker-benchmark.sh` | process/dispatch/atomic/trigger 小基线和并行压测;Kafka lag 采样 | 阶段 4、5、6 |
| worker stress 入口 | `load-tests/scripts/run-worker-stress-tests.sh` | import/export/dispatch/process 阶梯加压 | 阶段 2、3、4 |
| process 大数据入口 | `load-tests/scripts/run-process-worker-benchmark.sh` | aggregate/copy/idempotency 数据准备、运行和报告 | 阶段 4 |
| e2e 失败/恢复资产 | `batch-e2e-tests/src/test/java/com/example/batch/e2e/*Failure*`,`*Replay*`,`*Drain*`,`*RestartRecovery*` | failure pipeline、DLQ replay、worker drain、process restart recovery 等场景 | 阶段 4、5、6 |

### 阶段 0 初步缺口

| 缺口 | 需要补的内容 | 对应阶段 |
|---|---|---|
| 统一业务矩阵入口 | 已完成；默认 smoke 覆盖 `2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c` | 阶段 7 |
| Import XML/FIXED_WIDTH 系统级链路 | 已完成；剩 checkpoint 崩溃续跑需 fault injection | 阶段 2 |
| Export 格式矩阵 | 已完成小规模 DELIMITED/JSON/FIXED_WIDTH/EXCEL + 4/8 分片；真实 S3/multipart abort 待故障注入 | 阶段 3 |
| Process failure profile | 已完成 validation/empty/幂等/staging cleanup/分片；RUNNING cancel/timeout 语义待修 | 阶段 4 |
| Dispatch/Atomic mock failure | 已完成 HTTP 4xx/5xx/no-retry、SFTP/NAS/local sidecar、Atomic HTTP/timeout/cancel signal | 阶段 5 |
| Trigger cron/misfire | 已完成 scheduled fire/misfire pending/replay approve；pending->catch-up 自动关联待修 | 阶段 6 |

## 完成状态

| Worker | 代码优化状态 | Benchmark 状态 | 当前结论 |
|---|---|---|---|
| import | P0/P1 已完成并合入 | 1000w replace-copy / stage-swap / 分片 guard 已系统复验 | 大数据正确性完成;参数矩阵后续可继续细化 |
| export | P0/P1 已完成并合入 | 1000w 单片/4 分片正确性、multipart、参数读取、4 分片真并行已系统复验 | 真并行已达成;剩余是更高分片数/真实 S3 矩阵 |
| process | P1 DIRECT fast path 已完成 | 1000w 聚合和 1000w copy 已跑,DIRECT copy 62.978s | 本轮完成;后续只剩分片/失败恢复扩展 profile |
| dispatch | benchmark 入口、小基线、T1/T2 终态修复完成 | 修复后高压 180 launch:123 SUCCESS、57 FAILED、0 非终态 | worker 执行快;失败为本地 quota reject 的终态化 |
| atomic | benchmark 入口、小基线、T1/T2 终态修复完成 | 修复后 direct+trigger 合计 360 launch:230 SUCCESS、130 FAILED、0 非终态 | SQL executor 成功;stored-proc/http/shell 是可选矩阵 |
| trigger | scheduler read 并行、Kafka lag 采样、T1/T2 终态修复完成 | 高压 POST/read 900/900 OK,下游 360 atomic 全终态 | API/读压通过;高频 cron/去重仍是独立矩阵 |

## 统一方法

### 执行原则

- **只用真实链路**:正常 API 触发,让 trigger/orchestrator/Kafka/worker 自然流转。
- **先基线后优化**:没有 baseline 不改参数;没有三轮数据不下结论。
- **代码和 benchmark 分离**:代码完成写"已完成";性能收益只有复跑后才能写"已验证"。
- **每项改动可回滚**:参数矩阵一次只改一组,记录回滚命令。
- **按 P0/P1/P2 控制范围**:P0 是必须消除的吞吐/正确性瓶颈;P1 是高价值参数和局部优化;P2 是重架构或低确定性优化。

### 统一结果表

每类 worker 的结果统一沉淀到下表格式:

| Worker | 场景 | 数据量 | 并发/分片 | 耗时 | 吞吐 | CPU | IO | Kafka lag | 错误率 | 结论 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| | | | | | | | | | | |

### 统一采样

| 指标 | 采样来源 |
|---|---|
| 任务耗时 | `job_instance`,`job_task`,`pipeline_instance`,`file_record` 时间戳 |
| worker stage 耗时 | stage progress / task report / worker 日志 |
| DB 写入/查询耗时 | `pg_stat_statements`,业务表 count,staging 表 count |
| PG CPU/IO/WAL | container stats,`pg_stat_bgwriter`,`pg_stat_database`,`pg_stat_user_tables` |
| Kafka lag | consumer group lag,topic partition 分布 |
| 错误率 | task status、DLQ、`event_outbox_retry`,`event_delivery_log` |
| 文件大小/对象存储 | `file_record.file_size`,MinIO object metadata,STORE stage 耗时 |

### 当前脚本入口

| 脚本 | 覆盖 | 输出 | 说明 |
|---|---|---|---|
| `load-tests/scripts/run-worker-load-tests.sh` | import/export/dispatch/process 小基线 | `target/worker-load-report-<RUN_ID>.md` | 历史四类 worker 综合入口 |
| `load-tests/scripts/run-worker-stress-tests.sh` | import/export/dispatch/process 阶梯加压 | `target/worker-stress-report-<RUN_ID>.md` | `STEPS_CSV=1,2,4,8` |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | process/dispatch/atomic/trigger | `target/control-plane-worker-report-<RUN_ID>.md` | 默认顺序小基线;`CONTROL_PLANE_MODE=parallel` 跑真并行 |
| `load-tests/scripts/sample-scheduler-backlog.sh` | scheduler/backlog SQL 采样 | CSV | trigger 压测时由新脚本并行启动 |

## 2026-06-07 控制面小基线

| Worker | 场景 | 数据量 | 并发/分片 | 耗时 | 吞吐 | Kafka lag | 错误率 | 结论 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| process | `lt_process_sql_job` | 2 pipeline,每个 5000 row seed | `USERS=2` | P95 0.655s | 小基线,不作为上限 | 未采到 | 0% | 链路成功 |
| dispatch | `lt_dispatch_local_job` | 2 pipeline | `USERS=2` | P95 2.818s | 小基线,不作为上限 | 未采到 | 0% | 链路成功 |
| atomic | `atomic_sql_demo` direct | 2 pipeline | `USERS=2` | P95 3.065s | 小基线,不作为上限 | 未采到 | 0% | SQL executor 成功 |
| trigger | `atomic_sql_demo` launch | 30 launch + 60 scheduler read | launch/read 各 1 rps | POST P95 77ms | 1.03 launch/s | 未采到 | 0% | trigger 读写并行成功 |

详细报告见 `docs/verifications/control-plane-worker-throughput-2026-06-07.md`。

## 2026-06-07 控制面真并行复验

| Worker | 场景 | 数据量 | 并发/分片 | 耗时 | 吞吐 | Kafka lag | 错误率 | 结论 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| process | `lt_process_sql_job` | 20 pipeline | 1 launch/s | P95 1.269s(成功子集) | 1 launch/s | 0 | 0% HTTP,6 非终态 | 暴露 stale CREATED |
| dispatch | `lt_dispatch_local_job` | 20 pipeline | 1 launch/s | P95 1.029s(成功子集) | 1 launch/s | 0 | 0% HTTP,6 非终态 | 暴露 stale CREATED |
| atomic | `atomic_sql_demo` direct | 20 pipeline | 1 launch/s | P95 1.102s(成功子集) | 1 launch/s | 0 | 0% HTTP,4 非终态 | 暴露 stale CREATED |
| trigger | `atomic_sql_demo` launch | 20 launch + 40 scheduler read | launch/read 各 1 rps | HTTP P95 65ms | 1 launch/s | 0 | 0% HTTP,5 非终态 | 暴露 stale CREATED |

本轮结论:真并行压测能力达成;Kafka lag 采样达成;系统暴露 launch T1/T2 恢复缺口。后续已补普通 job 在资源调度 fail-fast 下的终态语义,见下一节复验。

### 追加高压复验

RUN_ID:`ctlw-20260607231538`

```bash
SKIP_AUTO_CLEANUP=1 \
CONTROL_PLANE_MODE=parallel \
MODULES_CSV=dispatch,atomic,trigger \
DISPATCH_LAUNCH_RPS=3.0 \
ATOMIC_LAUNCH_RPS=3.0 \
TRIGGER_LAUNCH_RPS=3.0 \
TRIGGER_READ_RPS=3.0 \
TRIGGER_DURATION_SECONDS=60 \
WAIT_TERMINAL_MIN_INSTANCES=540 \
bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

结果:

| 模块 | Job | 实例数 | 成功 | 非终态 | Task 成功数 | P95 claim | P95 exec | 结论 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| dispatch | `lt_dispatch_local_job` | 180 | 147 | 33 | 147 | 1.236s | 0.082s | worker 执行快,但 T2 分发缺口导致 CREATED |
| atomic direct | `atomic_sql_demo` | 180 | 140 | 40 | 140 | 1.266s | 0.026s | SQL executor 正常,CREATED 无 task |
| trigger -> atomic | `atomic_sql_demo` | 180 | 150 | 30 | 150 | 1.364s | 0.029s | trigger HTTP 正常,下游 launch 状态机残留 |

Gatling 层 `900/900 OK,0 KO`;Kafka lag 为 0;`ATOMIC`/`DISPATCH` worker 在线且 current_load 回 0。失败点集中在 orchestrator `StaleCreatedLaunchRecoveryScheduler` 反复报 `error.partition.dispatch_business_error`。这轮不应标记为 5 类 worker 全部高压完成,而应作为 P0 缺陷输入。

代码修复:

- `DefaultLaunchService` 在 T2 `error.partition.dispatch_business_error` 后把普通 `job_instance CREATED` CAS 标为 `FAILED`。
- 同一修复把对应 `trigger_request` 标为 `REJECTED`,避免长期 `ACCEPTED`。
- `DefaultLaunchServiceTest` 已补覆盖;目标测试通过。

### T1/T2 终态修复后复验

RUN_ID:`ctlw-202606080130-t1t2`

```bash
SKIP_AUTO_CLEANUP=1 \
CONTROL_PLANE_MODE=parallel \
MODULES_CSV=dispatch,atomic,trigger \
DISPATCH_LAUNCH_RPS=3.0 \
ATOMIC_LAUNCH_RPS=3.0 \
TRIGGER_LAUNCH_RPS=3.0 \
TRIGGER_READ_RPS=3.0 \
TRIGGER_DURATION_SECONDS=60 \
WAIT_TERMINAL_TIMEOUT_SECONDS=900 \
WAIT_TERMINAL_MIN_INSTANCES=540 \
MAX_ERROR_PCT=20.0 \
bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

结果:

| 模块 | Job | 实例数 | SUCCESS | FAILED | 非终态 | 结论 |
|---|---|---:|---:|---:|---:|---|
| dispatch | `lt_dispatch_local_job` | 180 | 123 | 57 | 0 | quota reject 已终态化为 FAILED/NO_TASK |
| atomic direct + trigger | `atomic_sql_demo` | 360 | 230 | 130 | 0 | SQL 成功子集正常;拒绝子集不再滞留 CREATED |

Gatling `900/900 OK,0 KO`;报告:`load-tests/target/control-plane-worker-report-ctlw-202606080130-t1t2.md`;Gatling HTML:`load-tests/target/gatling-results/controlplanemixedpressuresimulation-20260607173104566/index.html`。

结论:T1/T2 状态机 P0 已收口。高压下的 FAILED 是本地容量策略 `REJECT` 的可观察终态,不是 worker 卡死或未派发残留。

## 执行顺序

| 顺序 | Worker | 原因 | 预计阶段 |
|---:|---|---|---|
| 1 | import/export 复验 | 代码已完成,需要收口结果 | P0 收尾 |
| 2 | process | PG/staging/聚合风险最大 | P0 |
| 3 | dispatch/atomic | 控制面吞吐与背压影响全链路 | P0/P1 |
| 4 | trigger | 调度触发高频但可独立验证 | P1 |

## Import Worker

### 目标指标

| 指标 | 说明 |
|---|---|
| 行数/s | 端到端成功导入行数 / 总耗时 |
| COPY/UPSERT 吞吐 | LOAD 阶段分别统计 COPY、batch UPSERT、replace-copy |
| 分片 | 单大文件分片、partitionCount、fail-fast 语义 |
| 分区替换 | `PARTITION_REPLACE_COPY` 与 `PARTITION_STAGE_SWAP_COPY` |
| 错误率 | bad record、validation error、LOAD failure、DLQ |

### P0

| 项 | 状态 | 验证 |
|---|---|---|
| 1000w replace-copy 基线 | 已完成 | 现有报告已记录 |
| `PARTITION_STAGE_SWAP_COPY` | 已完成并复验 | 1000w 真实链路成功,对比 replace-copy 已记录 |
| 单大文件 replace-copy 分片 fail-fast | 已完成 | 负向验证应稳定 `IMPORT_LOAD_CONFIG_INVALID` |
| COPY/UPSERT/direct replace COPY micro benchmark | 已完成 | 保留脚本和现有结论 |

### P1

| 项 | 状态 | 验证 |
|---|---|---|
| `work_mem` / `maintenance_work_mem` | 已完成 | 结构化 session 参数已支持并单测验证;三轮矩阵转后续容量画像 |
| `chunk_size` / JDBC batch size | 已配置并复验主链路 | `chunk_size=10000` 已用于 1000w;JDBC batch 对 replace-copy 非主收益 |
| JVM/GC 参数 | 后续容量画像 | 需要固定重启窗口;不作为 P1 阻塞 |
| index rebuild 与 staging/swap 组合 | 已由 stage-swap 主链路收口 | 单独 drop/rebuild 只在后续矩阵里测,不作为 P1 阻塞 |

## Export Worker

### 目标指标

| 指标 | 说明 |
|---|---|
| 查询吞吐 | GENERATE 阶段 rows/s |
| 对象存储吞吐 | STORE 阶段 bytes/s |
| multipart | complete/abort 正确性,STORE 段耗时 |
| 分片导出 | 4/8 分片无重无漏,并行 wall time |
| 文件大小 | 输出对象大小、格式差异 |

### P0

| 项 | 状态 | 验证 |
|---|---|---|
| 1000w 单片基线 | 已完成 | 现有报告已记录 |
| 1000w 4 分片正确性 | 已完成 | 无重无漏已验证 |
| dispatch Kafka key 按分片稳定路由 | 已完成并复验 | trace `bb7343da2bd24313b8abbb99b8807c1f`,4 task 同秒 RUNNING,Kafka lag=0 |

### P1

| 项 | 状态 | 验证 |
|---|---|---|
| S3/MinIO multipart | 已完成并复验 | 复跑 1GiB+ 输出,4 分片 STORE 段 `12.1-15.5s/片` |
| `fetch_size` / `page_size` / `chunk_size` | 已完成并复验 | `page/fetch=5000`,`chunk=10000` 跑通 1000w;全局默认不放大 |
| `query_param_schema` / keyset-range 读取 | 已完成并复验 | 真实模板 API 已验证 `query_param_schema`/cursor/fetch 配置读取;keyset-range 作为可选模板能力保留 |
| export JVM/GC 参数 | 后续容量画像 | 需要固定重启窗口;当前 P0 已由真并行收口,不作为 P1 阻塞 |

## Process Worker

### 目标指标

| 指标 | 说明 |
|---|---|
| 计算/聚合吞吐 | input rows/s、output rows/s、SQL compute 耗时 |
| staging 写入 | `process_staging` insert/upsert rows/s |
| PG CPU/IO | 聚合 SQL、索引维护、WAL、checkpoint |
| 分区表膨胀 | staging 表 dead tuples、partition size、VACUUM 压力 |
| 幂等重跑 | 同 batch_key 重跑、失败中断后恢复 |

### P0

| 项 | 状态 | 结果 |
|---|---|---|
| process 1000w 聚合基线 | 已完成 | `lt_process_sql_job`:1000w -> 10w,端到端 40.966s |
| process 1000w copy 基线 | 已完成,性能不达标 | `lt_process_copy_job`:1000w -> 1000w,端到端 867.606s |
| staging 写入压力 | 已定位 | JSONB staging COMPUTE 440.191s,COMMIT 348.704s,WAL 增约 19GB |
| Kafka 长任务稳定性 | 已修配置 | process `max.poll.interval.ms` 默认 1200000ms,避免长任务 rebalance 重投 |
| SQL timeout | 已修配置 | process SQL transform 默认 900s,local profile 同步可配置 |
| 分区表健康 | 已观察 | 本轮 staging live rows 结束为 0,但 copy cleanup 44.577s,后续需优化大 batch cleanup |
| 幂等重跑 | 小基线已覆盖 | 大数据失败恢复/中途恢复另开 failure profile |

### P1

| 项 | 动作 | 取舍 |
|---|---|---|
| staging 分区/索引矩阵 | 按 biz_date/batch_key 维度测不同索引 | 只保留对主查询有收益的索引 |
| 批量写入优化 | batch size、COPY staging、UNLOGGED 临时表 POC | UNLOGGED 只允许中间暂存,不进业务结果表 |
| typed/direct copy | 已完成 `stagingMode=DIRECT` | 1000w copy 62.978s,比旧 JSONB 867.606s 明显收敛 |
| cleanup 压力 | orphan cleanup batch size / retention | 不影响正常 process 执行 |
| PG session 参数 | work_mem、temp_buffers | 只对 process worker session 生效 |

## Dispatch / Atomic Worker

### 目标指标

| 指标 | 说明 |
|---|---|
| 任务派发速率 | outbox → Kafka → worker claim tasks/s |
| Kafka lag | per topic / per partition / consumer group lag |
| 任务领取延迟 | dispatchAt 到 CLAIMED/RUNNING 时间 |
| 并发 lease | active lease 数、renew 成功率、renew latency |
| 失败重试/背压 | retry storm、DLQ、circuit/backpressure 行为 |

### P0

| 项 | 动作 | 完成标准 |
|---|---|---|
| 控制面高压基线 | 已完成 | `ctlw-202606080130-t1t2`:Gatling `900/900 OK`,Kafka lag=0 |
| topic partition 分布 | 已采样 | dispatch/atomic/trigger 正常消费,无长期积压 |
| lease/claim/report 压力 | 已验证主路径 | 成功子集 claim/exec 正常;T1/T2 fail-fast 已终态化 |
| 失败重试/背压终态 | 已修复并复验 | 容量策略 `REJECT` 下失败实例进入可观察终态,无 `CREATED + NO_TASK` 残留 |

### P1

| 项 | 动作 | 取舍 |
|---|---|---|
| consumer concurrency 矩阵 | 后续容量画像 | P0/P1 主链路已无积压;1/2/4/8 只用于上限测算 |
| outbox poll batch | 后续容量画像 | 当前不牺牲公平性去改默认值 |
| claim/report HTTP 批量化 | 暂不改协议 | 当前瓶颈不是 worker 执行慢;只在 task storm 暴露后启动 |
| atomic executor 分类 | 后续 profile | SQL/HTTP/stored-proc/shell 分开测;不影响本轮 P1 收口 |

## Trigger Worker

### 目标指标

| 指标 | 说明 |
|---|---|
| 触发延迟 | scheduled fire time 到 trigger_request/outbox 写入 |
| 批量触发吞吐 | triggers/s、launch/s |
| 去重/幂等 | requestId/dedupKey 冲突和重放 |
| Quartz/DB 扫描压力 | qrtz 表查询、trigger_runtime_state、DB locks |
| 下游影响 | trigger_outbox → orchestrator launch lag |

### P0

| 项 | 动作 | 完成标准 |
|---|---|---|
| 批量 API trigger 基线 | 已完成 | control-plane run 覆盖 API launch 和 scheduler read 并行压测 |
| 定时触发密集窗口 | smoke 已完成；容量画像后续 | Stage 6c 已覆盖 scheduled fire；高频 cron/misfire 矩阵保留为容量 profile |
| 去重幂等 | 已覆盖主路径 | requestId/dedup 主路径未出现重复 launch |
| trigger_outbox 积压 | 已采样 | 高压复验 Kafka lag=0,无长期积压 |

### P1

| 项 | 动作 | 取舍 |
|---|---|---|
| Quartz scan/index | 后续容量画像 | 当前 P1 已通过 API launch + scheduler read 并行压测收口 |
| batch size / poll interval | 后续容量画像 | 只在持续 fire QPS 达触发线后调整 |
| misfire 策略 | smoke 已完成；设计缺口另修 | Stage 6c 已覆盖 pending；pending->catch-up 自动关联缺口不阻塞本轮 P1 |
| wheel scheduler ADR 复核 | 只在持续高 fire QPS 达触发线后启动 | ADR-033 是后续重架构,不是本轮 P0 |

## 交付物

| 交付物 | 路径 | 说明 |
|---|---|---|
| 总控计划 | 本文档 | 所有 worker 的计划和状态 |
| import/export 详细报告 | `docs/backlog/single-node-throughput-optimization-2026-06-06.md` | 已有,继续维护 |
| 控制面 worker 小基线报告 | `docs/verifications/control-plane-worker-throughput-2026-06-07.md` | process/dispatch/atomic/trigger 本轮统一报告 |
| process worker 大数据报告 | `docs/verifications/process-worker-throughput-2026-06-07.md` | 1000w aggregate/copy 结果与瓶颈结论 |
| 控制面压测入口 | `load-tests/scripts/run-control-plane-worker-benchmark.sh` | process/dispatch/atomic/trigger 共用入口 |
| Worker 业务场景矩阵报告 | `docs/verifications/worker-business-scenario-matrix-2026-06-08.md` | 本地真实上下游 sim Stage 1 + Stage 2/2b/2c/2d import + Stage 3/3b/3c export + Stage 4/4b/4c process + Stage 5/5c dispatch/atomic + Stage 6/6c trigger 结果 |
| Worker 业务场景矩阵入口 | `load-tests/scripts/run-worker-business-scenario-matrix.sh` | 默认 smoke 串行复跑 Stage `2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c`;Stage 2d 需 skip profile 显式运行 |

## Checklist

- [x] import/export P0/P1 代码完成并合入 PR #418/#423
- [x] import/export 文档完成态已更新
- [x] import 1000w stage-swap 系统复验
- [x] export 1000w 4 分片并行 + multipart STORE 系统复验
- [x] process worker P0 小基线
- [x] 控制面真并行入口
- [x] Kafka lag 采样可用化
- [x] stale CREATED launch T2 恢复器代码
- [x] stale CREATED 恢复器系统复验
- [x] process worker P0 大数据基线与配置修复
- [x] process worker P1 typed/direct copy 优化
- [x] dispatch/atomic worker P0 小基线
- [x] dispatch/atomic worker P0 高压优化/修复
- [x] trigger worker P0 小基线
- [x] trigger worker P1 API launch + scheduler read 并行压测
- [x] 本地真实上下游 sim Stage 1(import/export/dispatch/workflow)业务场景验证
- [x] 本地真实上下游 sim Stage 2 import XML/FIXED_WIDTH 成功态、解析失败、字段校验失败
- [x] 本地真实上下游 sim Stage 3 export JSON/FIXED_WIDTH/EXCEL 成功态、bad SQL 失败态
- [x] 本地真实上下游 sim Stage 4 process JSONB/DIRECT/validation fail/empty success
- [x] 本地真实上下游 sim Stage 5 atomic SQL/shell/stored-proc 成功、dispatch HTTP 500 retry/no-retry 补偿语义
- [x] 本地真实上下游 sim Stage 6 trigger requestId 去重
- [x] 本地真实上下游 sim Stage 2b import UPSERT/LOAD failure/partition guard
- [x] 本地真实上下游 sim Stage 3b export keyset-range 4 分片
- [x] 本地真实上下游 sim Stage 4b process 幂等重跑/staging 恢复
- [x] 本地真实上下游 sim Stage 5b dispatch no-retry 补偿 + atomic 终态脚本
- [x] 本地真实上下游 sim Stage 6b trigger 30 请求 storm
- [x] 本地真实上下游 sim Stage 7 统一入口 smoke 扩展到 Stage 2/2b/3/3b/4/4b/5/6
- [ ] trigger 高频 cron / misfire 参数矩阵(非 P0/P1;后续可选容量 profile)
- [x] process/dispatch/atomic/trigger 共用 benchmark 脚本入口

## 不做项

| 项 | 决策 | 原因 |
|---|---|---|
| 直接上 Citus | 暂不做 | 单机和 worker 级 benchmark 未跑完 |
| 用前台操作模拟 benchmark | 不做 | 必须走真实系统链路 |
| 只看单测当性能结论 | 不做 | 单测只能证明代码路径,不能证明吞吐收益 |
| 全局调大 page/fetch/chunk 默认值 | 暂不做 | 可能伤害小租户/小内存场景,先模板级或 benchmark profile |
| trigger 立即替换 Quartz | 不做 | 需达到 ADR-033 触发线后再启动 |
| atomic shell 默认压测 | 不做 | shell executor 默认关闭,本地安全基线不打开 |
| remote SFTP/NAS/EMAIL/OSS 默认压测 | 不做 | 需要外部依赖或故障注入环境,放到可选矩阵 |
