# 整批量日 Dry-run 本地验收报告

日期：2026-09-11

分支：`feature/backend-optimization`

基线提交：`66802f215`（叠加本报告所列未提交修复）

运行基线：JDK 21 应用镜像，本地 Docker 单机 PostgreSQL、Kafka、MinIO、SFTP 与五类 Worker

## 1. 验收结论

本地功能、容量阶梯、故障恢复和混合流量验收通过。五类 Worker 共 100 条 dry-run 全部进入终态，
业务库、MinIO、SFTP 和本地文件目录的前后快照完全一致；10,000 条容量轮全部成功，Kafka 最终积压为 0。

生产功能开关继续保持默认关闭。本报告证明当前实现具备进入 staging canary 的条件，不把本地单机结果
等同于生产容量承诺，也不替代生产凭据、真实 S3/NAS 和网络策略下的最终验收。

## 2. 验收总表

| 场景 | 结果 | 核心证据 |
|---|---|---|
| 五类 Worker 功能基线 | PASS | Import 23、Export 23、Process 23、Dispatch 23、Atomic 8，共 100/100 成功 |
| 零业务副作用 | PASS | `batch_business` 全库、MinIO、SFTP、本地文件清单与校验和前后完全一致 |
| 100 → 1,000 → 10,000 容量阶梯 | PASS | 最大轮 10,000/10,000 entry、instance、task 全终态 |
| entry claim 后恢复 | PASS | 过期 `RUNNING` entry 自动回收并完成，无重复实例 |
| instance 创建后 Orchestrator 崩溃 | PASS | 100 条恢复轮 36 秒完成，100 个唯一实例，无重复 attempt |
| Worker 执行中崩溃 | PASS | 1,000 条最终全部成功，无重复实例和残留运行态 |
| dry-run 与正式任务混压 | PASS | 正式任务在 dry-run 仅完成 574/5,000 时已完成，无饥饿、幂等键无重叠 |
| Kafka 最终积压 | PASS | 五类 Worker 与 Trigger launch 六个消费组 `lag_sum=0`、`lag_max=0` |
| 配置与发布物同步 | PASS | registry/defaults/Helm 同步检查与 `helm lint` 通过 |

## 3. 本轮修复

1. 将 replay dispatcher 的 `lockAtLeastFor`、`lockAtMostFor`、poll、批量大小和 claim timeout 配置化；
   benchmark 使用 1 秒最短持锁和 30 秒最长持锁，常规环境保持 15 秒和 1 分钟。
2. dry-run 调度优先级统一降为 0；等待队列先排正式任务，再按公平分与既有优先级排序。
3. 修复 `TaskContext` 对包含 null 的属性使用 `Map.copyOf` 导致 Atomic dry-run 失败的问题，同时保留防御性只读快照。
4. 补齐本地 Import/Dispatch Worker 的资源 capability，避免合法任务被误判为 `NO_AVAILABLE_WORKER`。
5. Import 默认生成路径加入稳定 task id，避免同一 replay trace 下多 entry 写入同一路径。
6. terminal reconciler 允许受控重试后的 session 从 `PARTIAL_FAILED` 收敛为 `SUCCEEDED`。

## 4. 五类 Worker 与零副作用

使用 session `188` 至 `192` 执行五类真实调度链路：dispatcher → launch → Outbox/Kafka →
claim/report → terminal reconcile。最终结果如下：

| Worker | 条数 | entry | instance | task |
|---|---:|---|---|---|
| Import | 23 | 23 `SUCCEEDED` | 23 `SUCCESS_DRY_RUN` | 23 `SUCCESS` |
| Export | 23 | 23 `SUCCEEDED` | 23 `SUCCESS_DRY_RUN` | 23 `SUCCESS` |
| Process | 23 | 23 `SUCCEEDED` | 23 `SUCCESS_DRY_RUN` | 23 `SUCCESS` |
| Dispatch | 23 | 23 `SUCCEEDED` | 23 `SUCCESS_DRY_RUN` | 23 `SUCCESS` |
| Atomic | 8 | 8 `SUCCEEDED` | 8 `SUCCESS_DRY_RUN` | 8 `SUCCESS` |

前后快照使用正确业务库 `batch_business`，并覆盖 MinIO 对象清单、SFTP `/home` 和本地 `/tmp/batch`。
四类快照逐一 `cmp` 均返回 0。Dispatch 使用有效本地渠道绑定，但 dry-run 在持久化投递记录和远端传输前短路。

Atomic 租户策略为 `max_running=8`、burst 2、共享公平组上限 6、超限策略 `REJECT`。一次性提交 8 条时，
2 条按策略被拒绝；空闲后通过受控重试完成。该行为符合当前硬配额契约，但调用方应按不超过 6 的并发节拍投放，
或由运维明确触发重试，不能把临时配额拒绝当作自动排队。

## 5. 容量阶梯

| 规模 | 场景 | 耗时 | 吞吐 | 结果 |
|---:|---|---:|---:|---|
| 100 | 五类混合基线 | 分类型完成 | - | 100/100 成功 |
| 1,000 | Process，优化前 | 309.5 秒 | 约 3.2 entry/s | 1,000/1,000 成功 |
| 1,000 | Process，锁节拍优化后 | 31.4 秒 | 约 31.9 entry/s | 1,000/1,000 成功 |
| 10,000 | Process，正式容量轮 | 183.1 秒 | 约 54.6 entry/s | 10,000/10,000 成功 |

10,000 条轮次的最大活动实例数为 221，最大 Outbox backlog 为 155，PG lock waiter 峰值为 1 且只短暂出现；
结束时非终态为 0、Outbox backlog 为 0。运行窗口未发现 OOM、连接池耗尽、lease circuit 或关键 Kafka 错误。

原瓶颈是固定 15 秒最短持锁配合每批 50 条，使吞吐被锁节拍限制。配置化后，benchmark 每批最多 500 条且
最短持锁 1 秒，吞吐提升约 17 倍。常规环境不采用 benchmark 参数，以免空轮询放大数据库压力。

## 6. 故障恢复

1. **entry claim 后、instance 创建前**：将一条 entry 置为超过 10 分钟的 `RUNNING`，重启 Orchestrator 后
   自动回收并完成，最终关联实例 `16568` 为 `SUCCESS_DRY_RUN`。
2. **instance 创建后**：500 条轮次在已有 2 条绑定时 `SIGKILL` Orchestrator。该轮暴露硬编码 5 分钟最长锁；
   修复后用 100 条重新验证，在已有 8 条绑定时中止，重启后 14 秒恢复推进、36 秒完成，100 个唯一实例，
   重复 attempt 组为 0。
3. **Worker 执行中**：1,000 条 Process 在 3 个任务 `RUNNING` 时中止 Worker，重启后 1,000/1,000 成功，
   唯一实例 1,000。恢复时出现 3 次 `error.task.already_claimed`，属于 Kafka 重投后的 CAS 拒绝，未造成丢失或卡死。

## 7. 正式任务混压

在 session `193` 的 5,000 条 Process dry-run 运行期间，通过真实 Trigger HTTP 提交一个正式 Process 请求。
HTTP 返回 200；正式实例 `18726`、正式 task 优先级 5，均成功。正式任务完成时 dry-run 仅完成 574/5,000，
证明等待队列的正式优先规则生效。dry-run task 优先级均为 0，5,000 个 dedup key 和 batch number 均唯一，
与正式任务无交集。

容量 entry 使用一次性数据库夹具物化，因为公共 session API 的职责是按日历生成候选，而不是接收上万条复制数据；
物化后所有执行均走真实控制面。HTTP 错误率 0 仅指实际提交的正式请求 1/1 成功，不虚构 10,000 次 HTTP 压测。

## 8. 最终健康状态

- 本轮 replay session 无活动记录，测试轮无 `CREATED/READY/RUNNING` 残留。
- 全部在线 Worker 的 `current_load` 合计为 0。
- Outbox backlog 为 0。
- 五类 Worker 和 Trigger launch 消费组总 lag、最大分区 lag 均为 0。
- 数据库仍有 2026-09-10 遗留的两个正式 workflow task 处于 `READY`，其实例已是 `PARTIAL_FAILED`；
  它们早于本轮测试，不属于 dry-run 残留，应按原测试数据清理策略处理。

## 9. 生产开关决策

当前结论为 **本地验收通过、允许进入 staging、生产默认关闭**。

1. staging 先使用常规节拍：`lockAtLeastFor=15s`、`lockAtMostFor=1m`、entry batch 50，最大活动 entry 200。
2. 使用生产等价 S3/NAS、密钥、网络策略和数据库规格复跑五类零副作用与三类崩溃窗口。
3. 观察至少一个完整业务日，确认无副作用、无持续 backlog、无正式任务 SLA 回退后，再以最大活动 entry 200 做生产 canary。
4. 任何业务快照变化、终态残留、Kafka 持续 lag 或正式任务饥饿均应立即关闭开关并 drain 当前 session。

本地 10,000 条数据说明控制面实现没有明显线性失控，但生产容量仍须由 staging 同规格结果确定。
