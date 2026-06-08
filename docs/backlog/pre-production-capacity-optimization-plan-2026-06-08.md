# 上线前容量压测与优化计划 - 2026-06-08

## 背景

基于当前已有压测结果，系统处于 **P0/P1 主链路已收口，可以进入上线前灰度准备，但还没有完成生产容量上限认证** 的状态。

已验证事实：

- import/export/process 已完成 1000w 级真实系统链路验证。
- process 旧 JSONB staging copy 瓶颈已通过 `stagingMode=DIRECT` 收敛，1000w copy 从 867.606s 降到 62.978s。
- dispatch/atomic/trigger 控制面高压已消除 `CREATED + NO_TASK` 残留，已测场景均能进入终态。
- Kafka lag 在已测场景下能归零，当前主瓶颈不在 Kafka 主链路。
- worker-core lease renew 误熔断已修正：业务拒绝续租不再打开 `orch likely unreachable` circuit。

当前仍不能声称完成的事项：

- 真实 S3 / OSS / NAS / SFTP / HTTP 下游故障下的容量与恢复能力。
- 多租户混压下的配额公平性、隔离性和 RLS 串租验证。
- 10w task storm 容量上限。
- trigger 高频 cron、misfire 补点风暴和 trigger_outbox 积压恢复。
- PG WAL / checkpoint / work_mem / JVM 生产参数矩阵。

## 上线门槛

| 阶段 | 门槛 | 放行建议 |
|---|---|---|
| P0 | 主链路复验、trigger misfire、PG 参数矩阵完成且无卡死/非终态 | 可以灰度 |
| P1 | 真实对象存储、dispatch/atomic 故障注入、process failure profile 完成 | 可以扩大流量 |
| P2 | 1w/10w task storm、多租户混压、容量上限画像完成 | 可以给出容量承诺 |

## P0 上线前必做

### 1. 提交并固化当前基线

目的：避免后续压测混入未确认代码。

动作：

- 将 worker-core lease renew 修复单独提交。
- 将 benchmark 文档和已有 orchestrator 本地改动分清楚提交。
- 压测前记录 commit sha、配置、环境和 RUN_ID。

验收：

- 工作树无未分类关键代码改动。
- 压测报告能反查到 commit sha。

### 2. 重跑 process / dispatch / atomic / trigger 混压

目的：确认 lease renew 修复后，高压期间不再误报 `orch likely unreachable`，并确认控制面仍全终态。

覆盖：

- process 高压。
- dispatch 高压。
- atomic 1w storm。
- process/dispatch/atomic/trigger 混压。

验收：

- `non_terminal=0`。
- Kafka lag 最终归零。
- 无 `CREATED + NO_TASK`。
- 无业务拒绝续租触发 `renew circuit OPENED`。
- 失败必须是明确 `FAILED` / `REJECTED`，不能卡 `RUNNING` / `CREATED`。

### 3. trigger misfire / cron 风暴专项

目的：验证真实调度恢复、补点和去重能力。

覆盖：

- 高频 cron。
- 调度暂停后恢复。
- misfire replay。
- trigger_outbox 积压恢复。
- requestId / dedup 重放。

验收：

- 不重复创建实例。
- 补点数量正确。
- trigger_outbox 最终清空。
- workflow / job_instance 全部进入终态。

### 4. PG 参数矩阵

目的：找到 import/process/export 大数据写入的生产推荐参数。

参数：

- `checkpoint_timeout`
- `max_wal_size`
- `synchronous_commit`
- `work_mem`
- `maintenance_work_mem`
- 目标表索引数量

验收：

- 每组至少跑 3 次，取中位数。
- 记录 wall time、WAL 增量、DB size、CPU/IO、失败率。
- 输出生产推荐参数和不推荐参数。

## P1 上线增强

### 5. export 真实对象存储专项

覆盖：

- 8 / 16 / 32 分片。
- 真实 S3 或生产同类 OSS。
- multipart。
- 多租户并发。
- 对象 checksum 校验。

验收：

- 分片真并行。
- 文件完整性正确。
- 失败可重试或明确失败。
- 不产生半成品误成功。

### 6. dispatch / atomic 故障注入

dispatch 覆盖：

- SFTP / NAS / OSS / HTTP 下游 5xx、timeout、断连、权限失败。
- sidecar manifest / chk 文件完整性。
- 幂等重投。

atomic 覆盖：

- HTTP timeout。
- stored-proc 失败。
- shell opt-in 安全路径。
- lease renew 极限。

验收：

- 重试次数正确。
- DLQ 正确。
- 无重复成功上报。
- 无卡 `RUNNING`。

### 7. process failure profile

覆盖：

- DIRECT copy 中途 kill worker。
- DB 临时断开。
- 幂等重跑。
- cleanup 压力。
- empty result / validation 分支。

验收：

- 可恢复。
- 无 staging 残留。
- 无重复写脏数据。

## P2 容量画像

### 8. 1w / 10w task storm

目标：

- 先做 1w 全成功策略版本。
- 再做 10w 上限。

观察：

- 当前配额策略是排队还是 fail-close。
- orchestrator CPU / DB / Kafka 哪个先到瓶颈。
- backlog 清空速度。
- p95 / p99 launch 延迟。

### 9. 多租户混压

覆盖：

- 至少 ta / tb / tc 三租户。
- 大租户持续高压。
- 小租户插入短任务。

验收：

- 大租户不能饿死小租户。
- quota 生效。
- RLS 无串租。
- 监控能按 tenant 拆分定位。

## 推荐执行顺序

1. 提交当前修复，固化压测基线。
2. 重跑 process/dispatch/atomic/trigger 混压。
3. 跑 trigger misfire 专项。
4. 跑 PG 参数矩阵。
5. 跑真实 S3 / OSS export。
6. 跑 dispatch / atomic 故障注入。
7. 跑 process failure profile。
8. 跑 1w / 10w task storm。
9. 跑多租户混压。

## 当前优先级判断

最值得先做的三项：

1. **重跑混压**：验证刚修的 lease renew 竞态和熔断语义。
2. **trigger misfire**：调度系统上线风险高，且当前覆盖不足。
3. **PG 参数矩阵**：大数据链路瓶颈最终落在 PG 写入和 WAL，这项最可能带来生产收益。

可以后置的事项：

- 10w task storm：适合做容量承诺，不适合作为当前第一优先级。
- Citus / 应用层分片：属于架构演进，不应混入本轮上线前整改。
- 全格式全外部依赖矩阵：应分批补，不阻塞 P0 灰度。
