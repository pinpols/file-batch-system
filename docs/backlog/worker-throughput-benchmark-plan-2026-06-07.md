# Worker 吞吐优化与 Benchmark 总控计划

> 状态:**P0/P1 已收口:import/export/process 完成 1000w 级 benchmark;dispatch/atomic/trigger 高压复验已消除 `CREATED + NO_TASK` 残留**。
> 日期:2026-06-07
> 范围:`batch-worker-import`,`batch-worker-export`,`batch-worker-process`,`batch-worker-dispatch`,`batch-worker-atomic`,`batch-trigger`,`batch-orchestrator`
> 关联:
> - [single-node-throughput-optimization-2026-06-06](./single-node-throughput-optimization-2026-06-06.md)
> - [citus-introduction-plan-2026-06-06](./citus-introduction-plan-2026-06-06.md)

## 结论

1. **import/export P0/P1 已完成并系统复验**。导入覆盖 1000w `PARTITION_REPLACE_COPY` / 分片 guard / stage-swap；导出覆盖 1000w 单片、4 分片正确性、multipart/store 段、参数读取和 4 分片真并行。最终导出 trace `bb7343da2bd24313b8abbb99b8807c1f`,4 个 task 同秒 RUNNING,instance wall `144.092s`。
2. **process 已完成 1000w 大数据 benchmark 和 P1 收口**。聚合 1000w -> 10w 端到端 40.966s；旧 JSONB copy 867.606s；新增 `stagingMode=DIRECT` 后 1000w copy 端到端 62.978s,staging 残留 0。
3. **dispatch/atomic/trigger 已完成小基线 + 高压复验**。小基线全绿；修复后高压 RUN_ID `ctlw-202606080130-t1t2` 的 HTTP/Gatling 900/900 OK,540/540 实例全部进入终态,不再残留 `CREATED + NO_TASK`。
4. **本地高压失败是容量策略下的可观察终态,不是 worker 卡死**。本地 `tenant_quota_policy.exceeded_strategy=REJECT`、`dispatch_queue` 只有 3 job / 6 partition,高压下部分实例被终态化为 `FAILED/NO_TASK`;这是预期的背压结果。
5. **当前还不是 1w/10w 容量上限结论**。P0 状态机缺陷已修,下一步 task storm、故障注入、重试/背压上限可独立跑。
6. 所有 benchmark 都走正常系统链路:API / trigger / orchestrator / Kafka / worker / DB,不走前台模拟;代码完成状态和 benchmark 结果分开记录。

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
| `work_mem` / `maintenance_work_mem` | 代码已完成 | 结构化 session 参数已支持;默认关闭,benchmark profile 可打开 |
| `chunk_size` / JDBC batch size | 已配置并复验主链路 | `chunk_size=10000` 已用于 1000w;JDBC batch 对 replace-copy 非主收益 |
| JVM/GC 参数 | 暂缓 | 需要固定重启窗口;不作为本轮 P1 阻塞 |
| index rebuild 与 staging/swap 组合 | 暂缓 | 只在 staging 新分区内测,转后续矩阵 |

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
| S3/MinIO multipart | 代码已完成 | 复跑 1GiB+ 输出,对比 STORE 段耗时 |
| `fetch_size` / `page_size` / `chunk_size` | 已完成并复验 | `page/fetch=5000`,`chunk=10000` 跑通 1000w;全局默认不放大 |
| `query_param_schema` / keyset-range 读取 | 代码已完成 | 用真实模板 API 复验 |
| export JVM/GC 参数 | 暂缓 | 需要固定重启窗口;当前 P0 已由真并行收口 |

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
| 1w/10w task 派发基线 | 通过 orchestrator 正常创建大量 atomic/dispatch task | 得到 task/s、P95 claim delay、Kafka lag |
| topic partition 分布 | 检查 key 分布和 consumer 并发 | 无单 partition 热点 |
| lease renew 压力 | 高并发任务下观察 batch renew/单 renew | renew 不成为瓶颈 |
| 失败重试风暴 | 注入部分 worker fail / downstream fail | 不压垮 outbox/Kafka/worker |

### P1

| 项 | 动作 | 取舍 |
|---|---|---|
| consumer concurrency 矩阵 | 1/2/4/8 并发与 topic partition 配套 | 以 lag 和 claim delay 决定默认值 |
| outbox poll batch | 调整 batch size、shard、priority | 不牺牲公平性 |
| claim/report HTTP 批量化 | 评估 batch claim/report 或连接池 | 先测,不先改协议 |
| atomic executor 分类 | SQL/HTTP/stored-proc/shell 分开测 | shell 默认关闭,只测 opt-in 风险路径 |

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
| 批量 API trigger 基线 | 1k/1w request 通过正常 API 写入 | P95 trigger latency 可量化 |
| 定时触发密集窗口 | 构造同一分钟大量 cron fire | 无明显 DB lock/scan 飙升 |
| 去重幂等 | 重复 requestId / misfire replay | 不重复 launch |
| trigger_outbox 积压 | 观察 trigger outbox publish/consume lag | 不长期积压 |

### P1

| 项 | 动作 | 取舍 |
|---|---|---|
| Quartz scan/index | 查慢 SQL 与 qrtz 表索引 | 不急着替换 Quartz |
| batch size / poll interval | 调整触发扫描和 outbox 发布批量 | 以 DB 压力和延迟平衡 |
| misfire 策略 | 压测补点风暴 | 防止重投放大 |
| wheel scheduler ADR 复核 | 只在持续高 fire QPS 达触发线后启动 | ADR-033 是后续重架构,不是本轮 P0 |

## 交付物

| 交付物 | 路径 | 说明 |
|---|---|---|
| 总控计划 | 本文档 | 所有 worker 的计划和状态 |
| import/export 详细报告 | `docs/backlog/single-node-throughput-optimization-2026-06-06.md` | 已有,继续维护 |
| 控制面 worker 小基线报告 | `docs/verifications/control-plane-worker-throughput-2026-06-07.md` | process/dispatch/atomic/trigger 本轮统一报告 |
| process worker 大数据报告 | `docs/verifications/process-worker-throughput-2026-06-07.md` | 1000w aggregate/copy 结果与瓶颈结论 |
| 控制面压测入口 | `load-tests/scripts/run-control-plane-worker-benchmark.sh` | process/dispatch/atomic/trigger 共用入口 |

## Checklist

- [x] import/export P0/P1 代码完成并合入 PR #418
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
- [ ] trigger 高频 cron / misfire 参数矩阵(后续可选容量 profile)
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
