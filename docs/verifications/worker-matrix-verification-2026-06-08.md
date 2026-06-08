# Worker 压测矩阵复核报告 - 2026-06-08

## 结论

本轮在本机 local profile 下补跑了三类可落地矩阵：控制面 1k 混压、atomic 1w task storm、worker stress 小阶梯、process 10w 行 aggregate/copy/idempotency。结论是：当前主链路没有复现 CREATED/NO_TASK 残留，压测后 Kafka lag 归零，未见 `ThrowableProxy`、`LettuceConnectionFactory is STOPPING` 或应用启动失败。

同时，本轮前台输出观察到两个需要单独处理的信号：历史 import 脏任务在 worker 启动后被消费并报格式/约束错误；process/dispatch/atomic 在高压期间出现过 lease renew circuit OPENED。复核后确认，process/dispatch 可按 taskId 和时间戳对上，是任务成功处理后续租 tick 仍拿到旧 active lease 快照导致的正常业务拒绝，不是 orchestrator 短暂不可达；atomic 终端输出被截断，不能逐 taskId 对齐，但同代码路径且压测无非终态，应按同类问题治理。

本轮之后 P0 已继续补齐：mixed 压力复验、atomic 1w storm、trigger misfire/cron/outbox 专项、PG 写入参数矩阵均已形成报告。仍不能声称“全矩阵已通过”的是 P1/P2：真实云 S3、多租户混压、dispatch/atomic 真实外部依赖故障注入、process kill/DB 断链恢复和 10w task storm。

## 环境

- 时间：2026-06-08 08:43-09:01 Asia/Shanghai
- Profile：`local`
- 服务：console 18080、trigger 18081、orchestrator 18082、import 18083、export 18084、dispatch 18085、process 18086、atomic 18087
- JVM：`--enable-native-access=ALL-UNNAMED -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off`
- PG 当前参数：
  - `work_mem=4096 kB`
  - `maintenance_work_mem=65536 kB`
  - `checkpoint_timeout=300 s`
  - `max_wal_size=1024 MB`
  - `min_wal_size=80 MB`
  - `wal_level=replica`
  - `shared_buffers=16384 8kB`
  - `synchronous_commit=on`

## 本轮执行

| 项 | Run ID | 命令摘要 | 结果 |
| --- | --- | --- | --- |
| 控制面 1k 混压 | `mx1k0608` | process/dispatch/atomic/trigger，120s，约 14 req/s | 1680 请求 OK，1200 实例全终态，non_terminal=0 |
| atomic 1w task storm | `mx10k608` | atomic，30 req/s，334s | 10020 请求 OK，10020 实例全终态，non_terminal=0 |
| worker stress | `wsmx0608` | import/export/dispatch/process，users=1/2/4 | 28 个实例全部 SUCCESS |
| process 10w 行 | `prmx0608` | aggregate/copy/idempotency，100000 rows，10000 accounts | 4 个实例全部 SUCCESS，copy 目标 100000 行，aggregate 目标 10000 行 |

原始报告：

- `load-tests/target/control-plane-worker-report-mx1k0608.md`
- `load-tests/target/control-plane-worker-report-mx10k608.md`
- `load-tests/target/worker-stress-report-wsmx0608.md`
- `load-tests/target/process-worker-report-prmx0608.md`

## 关键发现

1. 控制面高压不再残留 CREATED/NO_TASK。
   - `mx1k0608`：1200/1200 终态。
   - `mx10k608`：10020/10020 终态。
   - 压测后日志未出现 `NO_TASK`、`CREATED` 异常堆栈。

2. 10k atomic storm 发起侧稳定，但业务成功容量受当前本机配额/限流影响。
   - Gatling：10020 OK，0 KO，p95=17ms，p99=33ms。
   - 实例：6676 SUCCESS、3344 FAILED、0 non_terminal。
   - 解释：这是 fail-close/容量门限结果，不是挂死；如果上线目标要求“1w 全成功”，需要单独调大 atomic 作业配额、worker 并发、orchestrator backpressure 参数后重跑。

3. worker stress 小阶梯全部成功。
   - users=1/2/4 下 import/export/dispatch/process 均 SUCCESS。
   - export users=4 的 p95 为 13.640s，比其他 worker 慢，后续更高分片和真实对象存储仍要单独测。

4. process 10w 行成功，幂等重跑成功。
   - `lt_process_sql_job` 10w source、1w account 聚合成功。
   - `lt_process_copy_job` copy 与固定 batchKey 重跑成功。
   - staging 清空为 0。

5. 持久日志里仍有历史数据告警。
   - `orchestrator.log` 有旧数据触发的 `uk_file_record_no_checksum` DuplicateKey WARN。
   - 旧的 `lt_process_copy_job` pipeline 仍被 FileGovernanceScheduler 报 processing delay。
   - 本轮未见 `ThrowableProxy`、Redis stopping、APPLICATION FAILED。

6. 前台输出暴露了两个上线前应清理/复核的问题。
   - `worker-import` 启动后消费到历史 import 任务，出现 `ck_file_record_format` 和 `line shorter than record_length`；本轮 `wsmx0608` import 自身是 SUCCESS，但环境里仍有旧任务会污染启动日志和 DLQ。
   - `worker-process`、`worker-dispatch`、`worker-atomic` 在高压期间出现 `WorkerTaskLeaseRenewer` 的 `renew circuit OPENED`，文本为 `orch likely unreachable`。复核代码和前台时间序列后，process/dispatch 是任务已经成功处理后，续租 tick 又对旧 active lease 快照发起 renew；orchestrator 因 task 已不再满足 RUNNING/worker/invocation 条件而返回正常业务拒绝。已在 worker-core 修正：业务执行完成后进入 completing 状态，不再进入 renew snapshot，但仍参与 shutdown drain；同时业务拒绝续租不再打开 `orch likely unreachable` circuit，只有 batch renew transport 异常才打开。

## 用户指定矩阵覆盖状态

| 矩阵 | 状态 | 说明 |
| --- | --- | --- |
| import JVM/PG/work_mem/chunk/batch 参数矩阵 | 已补 PG 写入矩阵 | `pg-param-matrix-20260608142440` 完成 5 组 x 3 次；JVM 和 chunk/batch 系统级组合仍未做。 |
| trigger 高频 cron / misfire | 已补本地专项 | `scripts/sim/24-trigger-stage6d.sh` 通过；`trigger-stage6d-20260608163850` 复验 pending 自动关联 catch-up request；wheel 模式亚分钟连续 cron fire 仍不作为放行能力。 |
| 1w / 10w task storm | 部分覆盖 | `preprod-p0-atomic1w-20260608140503` 已跑 1w 全终态；10w 未跑，本机 local 环境不适合作为上线容量承诺。 |
| process 大数据失败恢复、幂等重跑、中途失败恢复 | 部分覆盖 | 10w aggregate/copy/idempotency 已跑；`process-stage4c-20260608163544` 复跑分片和 RUNNING cancel 中断；未做 kill worker、DB 中断、重启恢复。 |
| dispatch / atomic 故障注入、失败重试风暴、下游异常、lease renew 极限 | 部分覆盖 | `sim-dispatch-stage5b-20260608143154`、`sim-dispatch-stage5c-20260608143209`、`atomic-stage5c-20260608163608` 已复跑本地 500/manifest/timeout/shell cancel 中断；真实外部 timeout/断连/权限失败和 DLQ 组合未做。 |
| export 更高分片数、真实 S3、多租户混压 | 部分覆盖 | `sim-export-stage3c-20260608143456` 已复跑本地 MinIO/S3-compatible 8 分片、三租户、幂等；真实云 S3/OSS multipart abort/retry 未配置。 |

## 上线前仍建议补的硬项

1. 建一个可重复的故障注入 harness：暂停 Kafka/Redis/PG、模拟下游 5xx/timeout、验证重试和 DLQ；Import worker crash after chunk 已由 Stage 2e 覆盖。
2. trigger misfire pending 自动关联已补；后续只保留高频 cron、replay 数量上限和 1w storm 容量 profile。
3. 用真实对象存储跑 export：至少 8/16/32 分片、multipart、跨租户并发、对象校验和。
4. 做 PG 参数矩阵：重启 PG 后分别测 `work_mem`、`maintenance_work_mem`、checkpoint/WAL 参数，记录中位数而不是单次值。
5. 如果上线目标包含 1w 全成功，调大配额/并发后重跑 `mx10k608`，不能只满足“全终态”。
6. 清理或隔离历史 import/process 脏数据，避免 worker 启动后自动消费旧任务造成误报。
7. lease renew 可观测性和熔断语义已修正：业务拒绝续租不打开 `orch likely unreachable` 熔断；worker 本地增加 completing 状态，让任务处理完成、report 前的租约仍参与 drain，但不再进入 renew snapshot；409/404/renewed=false 与 5xx/timeout/连接失败已按业务拒绝和 transport failure 分开。
