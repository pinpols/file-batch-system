# ADR-015 · Worker 端本地 outbox — report 重投与防重复执行

- **Status**: Accepted（2026-05-03）：HTTP REPORT 耗尽重试后写入 **平台 PG**（默认，`batch.worker_report_outbox` / Flyway **V96**）或 **SQLite**（`storage=SQLITE`）；poller `SKIP LOCKED` 多副本抢占、`PUBLISHING` 陈旧回收、续租熔断协同；Kafka offset 仍在 listener 成功后提交（与 durable outbox 顺序一致）。业务 JDBC 与 outbox **同一 PG 事务**未实现（executor 线程模型限制）。
- **Date**: 2026-05-03
- **Related**: ADR-014 (CLAIM 幂等) / docs/analysis/worker-vs-industry-2026-05-03.md P1-10

---

## 背景

当前 `DefaultTaskExecutionWrapper.execute` 路径:

```
CLAIM → 业务 execute → REPORT (HTTP POST orch)
                          ↓ 失败
                       AbstractTaskConsumer.doConsume catch
                          ↓
                       publishToDlqSafely → DLQ
                          ↓
                       运维 replay → 重新派 task → 重新 execute → 业务跑两次
```

**漏洞**:

- 业务**已成功执行**,但 REPORT 因网络抖动 / orch GC pause / 短暂不可达失败
- 当前流程把它当成"任务失败"处理,送 DLQ → 运维 replay → orch 重派 → 业务重复执行
- `LeaseRenewer` 的熔断 (P1-8) 减轻了 orch 长不可达的连锁,但**业务已成功这种情况无关熔断**

业务结果的唯一可靠来源是 `REPORT` 落到 orch 的 `job_task_result`。REPORT 失败 = 结果丢失。

## Phase 1–2（已实现）

- **开关**：`batch.worker.report-outbox.enabled`（默认 `false`）；`**storage`**：`PLATFORM_PG`（默认，依赖 orchestrator Flyway **V96**）或 `SQLITE`（`sqlite-path`）。
- **写**：`HttpTaskExecutionClient.report` 在内置 HTTP 重试耗尽后，若开关开启且 outbox UPSERT 成功，则计量 `batch.worker.report.duration{outcome=deferred_outbox}` 并返回（不向调用方抛异常）。
- **读**：短事务内 **抢占**（NEW→`PUBLISHING`）；PostgreSQL 使用 `**FOR UPDATE SKIP LOCKED`**；SQLite 双语句近似独占。随后调用 `submitReportOverHttp`；成功 DELETE；失败退避并将状态回到 NEW，直至 `**max-publish-attempts`** → `**GIVE_UP**`。
- **陈旧恢复**：定时将超时仍停在 `**PUBLISHING`** 的行重置为 **NEW**（进程崩溃回退）。
- **续租熔断**：`pause-poll-when-renew-circuit-open=true`（默认）时，`WorkerTaskLeaseRenewer` 熔断 OPEN 则跳过 poll，避免 orch 不可达时无效 hammer。
- **未纳入**：业务 `**TaskExecutionPool`** 线程内 JDBC 与 outbox **同一数据库事务**（需更大重构）；Kafka **EOS / 事务型 producer** 未引入。

## 业界对比


| 系统                | 机制                                                                                   |
| ----------------- | ------------------------------------------------------------------------------------ |
| **Temporal**      | activity 完成后 worker 持久化结果到本地,SDK 自动 retry (exponential backoff) 直到 server 接收         |
| **Kafka Streams** | EOS (exactly-once semantics) 通过 transactional producer + sink 端 atomic offset commit |
| **Airflow**       | task instance 状态写本地 SQLite,scheduler 拉取 (polling) — 与本系统反向                           |


## 决策提案

**worker 端引入 outbox**：默认持久化至 **平台 PostgreSQL**（`batch.worker_report_outbox`），可选 **SQLite** 本地文件。

### 数据流（落地）

```
业务 execute → REPORT orch →（HTTP 耗尽失败）UPSERT outbox（PG 或 SQLite）
                  ↓ listener 成功返回
              Kafka ack offset
              outbox poll（SKIP LOCKED）→ HTTP REPORT orch → 成功 DELETE / 失败退避
```

### 关键语义

- orch 重派路径仍由 ADR-014 **invocation_id** 回退（过期 REPORT 被拒）。
- REPORT 重试与业务线程解耦；进程重启后 PG/SQLite 未投递行由 poller 续推。
- **同事务业务 JDBC + outbox** 未纳入（见上文「不变量（落地版）」）。

### 技术选型（落地）

- **PLATFORM_PG（默认）**：`batch.worker_report_outbox`，与 worker 既有 `**JdbcTemplate`/平台 datasource** 对齐；Flyway **V96**。
- **SQLITE（可选）**：无共享 PG / 沙箱场景；需持久卷指向 `sqlite-path`。

## 影响面

**正面**:

- 业务结果不再因 REPORT 失败而丢失 → 重派/重复执行场景大幅减少
- 与 ADR-014 invocation_id 协同:重派的"过期 invocation" REPORT 被 orch 拒收,不污染 active invocation

**负面**:

- worker 容器加 SQLite 文件依赖 (volume 挂载 / persistent storage)
- 本地 outbox poll loop + 状态机
- worker 进程崩溃后本地 outbox 文件不丢失才能保证 → K8s ephemeral pod 需要 PVC
- 工时:2-3 天
  - SQLite schema + mapper
  - Worker 端 outbox writer + poller + retry
  - 与现有 LeaseRenewer 熔断协同(orch 不可达时不浪费 retry)
  - 端到端测试覆盖"REPORT 失败 → 本地缓存 → 重启后重投" 场景

## 替代方案 (被拒绝)

### A. REPORT 改 Kafka 异步

orch 端从 HTTP `/internal/tasks/{taskId}/report` 改为 Kafka topic `batch.task.result.report`,worker producer 发,orch consumer 处理。

**优点**:Kafka 持久化 + 异步,自带重投。

**否决理由**:

- 协议 breaking change (老 worker 仍走 HTTP)
- worker 仍需要"同事务 producer + ack" 机制确保业务-发布原子性
- 复杂度类似本地 outbox,但增加 Kafka 流量

### B. orch 端 polling 替代 worker push

orch 周期 poll worker 的 `/internal/results` endpoint。

**否决理由**:

- 反 push/pull 方向,worker 暴露 endpoint 增加攻击面
- 延迟敏感场景不友好

## 验收标准

- ✅ 平台 PG：`batch.worker_report_outbox`（Flyway **V96**）与嵌入式 **SQLite**（`storage=SQLITE`）二选一落地。
- ✅ HTTP REPORT 失败后 UPSERT outbox；poller `SKIP LOCKED` 抢占、`PUBLISHING` 陈旧回收、退避、`GIVE_UP`；续租熔断 OPEN 时可暂停 poll。
- ○ 建议运维演练：模拟 orch 长时间不可达 → 恢复后自动 REPORT（可作为后续 E2E 补齐）。
- ○ invocation_id：载荷字段已由 worker/report orch 路径贯通；专用集成测可按需补强。

## 不变量（落地版）

- **Kafka offset**：listener 仅在 `execute` + `report`（含 deferred enqueue）路径完整返回后才 ack；**enqueue 成功先于 offset 提交**，避免「结果未写入数据库却已提交位移」。
- `**PLATFORM_PG`**：依赖平台 PostgreSQL 耐久（worker **flyway 默认关闭**，迁移由 orchestrator 先行）。
- `**SQLITE`**：`sqlite-path` 必须 PVC / 持久卷。
- **未承诺**：业务 **business** 库 JDBC 与 outbox **同一数据库事务**（`TaskExecutionPool` 异步执行模型限制）；后续若收紧需在 adapter 层引入统一单元事务边界。

## 替代讨论

参见 worker-vs-industry-2026-05-03.md P1-10。