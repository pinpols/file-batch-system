# Worker P2 本地容量画像报告 - 2026-06-08

## 结论

P2 已按本地环境执行，结论不是“全部通过”，而是得到上线前容量画像：

- process kill worker / PG 断链恢复已覆盖：worker kill 后同实例最终 SUCCESS；PG backend terminate 后当前实例明确 FAILED/INFRA_ERROR，同 batchKey 重跑 SUCCESS，staging 清空。
- 10w atomic storm 已跑到本机上限：100000 发起请求中 Gatling OK=41096、KO=58904；本地 HTTP/trigger/orchestrator 链路无法承载 200 rps 级 10w storm，不能作为上线容量承诺。
- storm 后恢复暴露两个瓶颈：trigger outbox pending 查询退化、orchestrator launch consumer 单线程/单队列追赶慢。
- 已做一个局部优化：`trigger_outbox_event` pending 查询改为按 `next_publish_at, created_at, id` 排序，并补 `V168__trigger_outbox_pending_order_index.sql`，EXPLAIN 已从 Sort/Bitmap Heap Scan 变为 `Index Scan using idx_trigger_outbox_event_pending_order`。
- 多租户公平性最终全成功，但过程不公平：ta/tb/tc 6000 请求最终 6000 SUCCESS；但 storm backlog 后 p2 请求发布完成到首批关联约 8 分钟，且 ta 先完成、tc 明显滞后。

## Process Failure Profile

- RUN_ID：`p2-process-failure-20260608171651`
- 报告：`load-tests/target/p2-process-failure-p2-process-failure-20260608171651.md`
- 规模：1000000 source rows，100000 accounts

结果：

| 场景 | 请求 | 结果 |
|---|---|---|
| kill worker after running | `...-kill-worker` | instance/task/partition 最终 SUCCESS，耗时 54.322s |
| PG backend terminate | `...-pg-disconnect` | 明确 FAILED，`task_error_code=INFRA_ERROR`，耗时 4.280s |
| 同 batchKey 恢复重跑 | `...-pg-disconnect-rerun` | SUCCESS，耗时 21.002s |

业务核对：

- `process_staging_rows=0`
- `source_rows=1000000`
- `process_event_copy_rows=1000000`

结论：process failure recovery 本地可恢复；PG 断链语义是当前实例失败、同 batchKey 重跑恢复，不是同实例自动续跑。

## 10w Task Storm

- RUN_ID：`p2-capacity-20260608171944-10w`
- Gatling report：`load-tests/target/gatling-results/controlplanemixedpressuresimulation-20260608092013832/index.html`
- 参数：100000 requests，200 rps，atomic

Gatling：

- OK：41096
- KO：58904
- 主要错误：
  - `java.net.ConnectException: Operation timed out`：30940
  - request timeout `127.0.0.1:18081` after 60000 ms：19972
  - request timeout `::1:18081` after 60000 ms：7992
- p95/p99 接近 60s timeout 上限。

数据库侧最终可见实例摘要：

| tenant | job | total | success | failed | non_terminal | p95 seconds |
|---|---|---:|---:|---:|---:|---:|
| default-tenant | atomic_sql_demo | 69060 | 45703 | 23357 | 0 | 0.399 |

结论：

- 10w 在当前本机 local profile 下不通过，瓶颈首先体现在 trigger HTTP 接入超时和后续 launch backlog。
- 已进入系统的实例最终无 non-terminal，但大量请求在接入层失败或被容量策略拒绝。
- 不能用这轮给生产 10w 容量承诺；需要单独做生产参数、分区/consumer 并发、trigger/orchestrator 横向扩展后的容量轮次。

## Trigger Outbox 优化

P2 storm 后发现 trigger outbox 大量 `NEW` 记录时，relay 卡在 `TriggerOutboxEventMapper.selectPending`。

修复：

- `batch-trigger/src/main/resources/mapper/TriggerOutboxEventMapper.xml`
  - `ORDER BY created_at` 改为 `ORDER BY next_publish_at, created_at, id`
- `db/migration/V168__trigger_outbox_pending_order_index.sql`
  - 新增部分索引：`(next_publish_at, created_at, id) WHERE publish_status IN ('NEW','FAILED')`

本地验证：

```text
Index Scan using idx_trigger_outbox_event_pending_order on trigger_outbox_event
  Index Cond: (next_publish_at <= CURRENT_TIMESTAMP)
  Filter: publish_status IN ('NEW','FAILED')
```

效果：

- trigger 重启后健康检查 UP。
- outbox backlog 从约 30366 开始恢复，约数千条/分钟推进。
- 这修掉了 pending 查询排序退化，但没有解决单 relay / 单 launch consumer 的队列公平性问题。

## 多租户公平性

- RUN_ID：`p2-fairness-trigger-20260608180000`
- 模式：trigger API，ta:tb:tc = 3:1:1
- 规模：6000 requests，concurrency=96

发起侧：

- launch_ok：6000/6000
- p95 launch：
  - ta：513 ms
  - tb：439 ms
  - tc：403 ms

最终状态：

| tenant | request_status | instance_status | count |
|---|---|---|---:|
| ta | LAUNCHED | SUCCESS | 3600 |
| tb | LAUNCHED | SUCCESS | 1200 |
| tc | LAUNCHED | SUCCESS | 1200 |

恢复时间线：

| 时间 | 状态 |
|---|---|
| 18:01 | p2 outbox 开始发布，535/6000 PUBLISHED |
| 18:04 | p2 outbox 6000/6000 PUBLISHED |
| 18:12 | 首批关联实例，465/6000 linked |
| 18:13 | linked 2513/6000，ta SUCCESS 1804、tb 563、tc 146 |
| 18:15 | linked 5618/6000，ta SUCCESS 3600、tb 1161、tc 851 |
| 18:16 | linked 6000/6000，ta/tb/tc 全 SUCCESS |

结论：

- 最终一致性 OK。
- 公平性不达标：大租户 ta 明显先完成，小租户 tc 明显滞后。
- storm 后恢复不达标：p2 outbox 全发布后约 8 分钟才开始被 launch consumer 关联。
- 下一步要做 tenant-aware trigger outbox selection / launch consumer 分区并发 / quota fair-share，而不是继续只调单机 JVM 参数。

## 稳定性 Smoke

- 时间：2026-06-08 18:31-18:40 Asia/Shanghai
- 采样：20 次，每 30 秒一次，约 10 分钟
- 报告：`load-tests/target/stability-20260608183104.log`
- 服务：console、trigger、orchestrator、import、export、dispatch、process、atomic

采样结论：

| 指标 | 结果 |
|---|---|
| 8 个服务健康检查 | 20/20 全部 UP |
| 最近 30 分钟 non-terminal | 0 |
| 最近 30 分钟 CREATED + NO_TASK | 0 |
| trigger_outbox | `PUBLISHED=106838`，`GIVE_UP=2` |

稳定窗口本身通过：没有新增卡住实例，没有新增 CREATED/NO_TASK，Kafka backlog 后的主链路保持终态收敛。

但这不是“干净环境全绿”，本轮稳定性检查同时暴露了几类历史残留风险：

1. `worker-process` 重启后消费到旧 Kafka dispatch 消息，曾把历史 PG 断链场景的 task `21946` 重新处理为 SUCCESS，但对应 instance `30319` 仍是 FAILED。这说明 terminal instance/task 的重复消费保护仍要加强：worker 执行前应拒绝 terminal instance 下的旧消息，或 orchestrator claim/report 侧做更硬的幂等保护。
2. `orchestrator.log` 仍每分钟出现旧 file_record 无 checksum 数据触发的 `uk_file_record_no_checksum` DuplicateKey WARN。该问题会污染稳定性日志，应补历史数据 cleanup 或让调度器对重复记录做幂等跳过。
3. `worker-import.log` 仍有历史 import 配置脏数据触发的反序列化 WARN；`worker-atomic.log` 仍有历史 atomic timeout 任务反复失败重试。后续压测前需要清理旧 task/request 或隔离测试 RUN_ID，避免把历史毒消息误判为本轮失败。
4. 本地 `restart.sh` 启动 trigger/process 在 Codex exec 生命周期下未能持久驻留，已改用 `screen` 后台方式补齐本轮稳定性检查；这是本地运行方式问题，不计入业务链路结论。

## 剩余风险

- 真实云 S3/OSS、真实 SFTP/NAS/HTTP 断链仍不在本轮 P2 范围内。
- 本轮是本地 local profile，不代表生产容量。
- 10w storm 后仍有部分历史 `atomic-*` trigger_request 保留在库中用于复核；后续复跑前应先 cleanup，避免污染延迟曲线。
