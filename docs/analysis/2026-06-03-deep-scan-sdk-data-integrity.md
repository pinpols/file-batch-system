# 深度扫描 2026-06-03:SDK 双语言一致性 + 数据完整性

> 作用域:`batch-worker-sdk/`(Java)+ `batch-worker-sdk-python/` 跨语言协议对照、`docs/api/sdk-shared-constants.yaml` 与 `docs/api/orchestrator-internal.openapi.yaml` 漂移、Flyway V1–V165 archive 镜像合规、outbox 三表分工、多租 UNIQUE / 守护、`ArchiveSchemaDriftCheck` 启动期守护、跨服务事务边界、soft-delete / 时区 / 编码静态扫描。
>
> 基线:`origin/main`(HEAD = `4d47f332`)。本次报告不改任何代码,只锁定真问题 + 真已对齐项,作后续整改/PR 拆分输入。
>
> 主要扫描手段:`grep -rn` 多模块对照、关键文件 `Read`、openapi vs controller `@PostMapping` 差表对、migration 文本对比 archive 关键字、`ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 列表回扫 V157+。

---

## TL;DR — 等级与数量

| 等级 | 数量 | 主题 |
|---|---|---|
| **P0** | 4 | Python SDK REPORT body 字段错(orch 读不到)、`/internal/workers/{code}/status` 方法错(GET vs POST)、Python idempotency-key 形态错(重复用 + 协议 §A 冲突)、Python 心跳 body 缺字段(`workerGroup`/`capabilityTags` 等),orch 心跳记录列被静默填 NULL |
| **P1** | 6 | Python config 文档表与字段名漂移、Python 缺 `strictTimingValidation` 开关、Python 缺 `worker_registry` build_id/sdk_version 心跳上报、`worker_registry` 缺 archive 镜像登记理由未文档化、`docs/api/sdk-shared-constants.yaml` 与 Python `constants.py` 中 `task_statuses` 用 `tuple` vs Java enum 顺序未做断言、`leases/renew-batch` 批量端点 Python 未消费(P1 性能潜风险) |
| **P2** | 4 | Python `_decode_body` 数组 → `{"_array": ...}` 包装回退属"防御性",但隐藏契约,未与 Java 对齐;Python 缺 `force-offline`/`takeover`/`warmup`/`claimed-tasks` 四个 internal-only 端点(语义为运维用,SDK 不必要,但应注释明示);`HeartbeatRequest` Java DTO 含 `processId/hostName/hostIp` Python 心跳 body 永不上报;`RetryScheduleMapper` 在 conditional-tenant 白名单上仍占位 |
| **OK** | 8 | archive 镜像 Flyway V160-V165 全部同 PR 补齐;V164/V165 多租 UNIQUE 守护齐备;`ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 覆盖 V159/V164/V165 新表;trigger/orchestrator/worker 三表分工无串用;时区(`ZoneId.systemDefault()` / `LocalDateTime.now()`)业务代码 0 命中;编码(`Charset.forName("UTF-8")` / 字面量)业务代码 0 命中;`MapperXmlTenantGuardArchTest` orchestrator + console-api 双覆盖;`SharedConstantsParityTest` + `SdkWireContractTest` Java 端契约锁齐 |

> **Java SDK 是 source-of-truth**(`docs/api/sdk-shared-constants.yaml` 顶部注释明确)。本次发现的 P0 全部出在 Python 端"以为对齐了但实际未对齐"的字段细节上;Java 侧通过 `SdkWireContractTest` 守护住了平台 DTO,所以问题没在 CI 中暴露。

---

## 1. SDK 双语言对照(端点 × DTO × 行为)

### 1.1 端点矩阵(workers / tasks)

orchestrator 实际 `@PostMapping` 见 `batch-orchestrator/src/main/java/com/example/batch/orchestrator/controller/{Worker,Task}Controller.java`,openapi 见 `docs/api/orchestrator-internal.openapi.yaml`。

| 端点 | Method (orch) | Java SDK | Python SDK | openapi | 备注 |
|---|---|---|---|---|---|
| `/internal/workers/register` | POST | ✓ | ✓ | ✓ | stable |
| `/internal/workers/{code}/heartbeat` | POST | ✓ | ✓ | ✓ | stable |
| `/internal/workers/{code}/deactivate` | POST | ✓ | ✓ | ✓ | stable |
| `/internal/workers/{code}/status` | **POST** | ✗(未暴露) | **写成 GET** ❌ | POST | **P0-2,详 §1.4** |
| `/internal/workers/{code}/drain` | POST | ✗(未暴露) | ✓ | POST | internal-only,Python 已接入 |
| `/internal/workers/{code}/force-offline` | POST | ✗ | ✗ | POST | internal-only,SDK 不必要 |
| `/internal/workers/{code}/takeover` | POST | ✗ | ✗ | POST | internal-only |
| `/internal/workers/{code}/warmup` | POST | ✗ | ✗ | POST | internal-only |
| `/internal/workers/{code}/claimed-tasks` | GET | ✗ | ✗ | GET | internal-only |
| `/internal/tasks/{id}/claim` | POST | ✓ | ✓ | ✓ | stable, 带 Idempotency-Key |
| `/internal/tasks/{id}/report` | POST | ✓ | ✓ | ✓ | stable, 带 Idempotency-Key |
| `/internal/tasks/{id}/renew` | POST | ✓ | ✓ | ✓ | stable |
| `/internal/tasks/{id}/cancel` | POST | ✗ | ✗ | (未检) | 仅平台触发 |
| `/internal/tasks/leases/renew-batch` | POST | ✗ | ✗ | (未检) | **P1-6**,批量续约性能优化未启用 |

### 1.2 wire DTO 对照(字段级)

源:`batch-worker-sdk/src/main/java/com/example/batch/sdk/wire/*.java` vs Python `dispatcher/dispatcher.py` + `scheduler/_heartbeat.py` + `client/client.py` 构建的 body dict。

#### RegisterRequest

| 字段 | Java record | Python body(`_build_register_body`) |
|---|---|---|
| tenantId | ✓ | ✓ |
| workerCode | ✓ | ✓ |
| workerGroup | ✓ | ✓(硬编码 `"sdk-self-hosted"`) |
| status | ✓ | ✓(硬编码 `"RUNNING"`) |
| hostName / hostIp / processId | ✓ | **缺失**(永不上报) |
| buildId | ✓(SDK-P5-3 运行指纹) | ✓ |
| sdkVersion | ✓ | ✓ |
| heartbeatAt | ✓(Instant) | ✓(`_utc_now_iso`) |
| capabilityTags | ✓(List<String>) | ✓(`sorted(handlers.keys())`) |
| currentLoad | ✓ | ✓(0) |
| taskTypes | ✓(SdkTaskTypeDescriptor) | ✓(`descriptor.model_dump`) |

#### HeartbeatRequest

Python `HeartbeatScheduler.tick` body 仅含 `{tenantId, workerCode, status, heartbeatAt, currentLoad}` — **缺** `workerGroup`、`hostName`、`hostIp`、`processId`、`capabilityTags`。

Java `HeartbeatRequest` 全部 10 字段都填(`HeartbeatScheduler.tick` 用 `BatchPlatformClient.buildHeartbeatBody`)。平台共用 `WorkerHeartbeatDto`,heartbeat 路径会刷新 worker_registry 的运维元数据列 → Python worker 心跳后这些列被静默写空。**P0-4**。

#### ClaimRequest / RenewRequest

Java/Python 一致(`tenantId` + `workerId` + 可选 `partitionInvocationId`)。✓

#### ReportRequest(**P0-1 最严重**)

Java `ReportRequest` 字段:`taskId / tenantId / workerId / traceId / success(bool) / code / message / resultSummary / errorCode / highWaterMarkOut / outputs / partitionInvocationId / failureClass / verifierFailures`。

Java dispatcher `TaskDispatcher.java` L298-309 实际 REPORT body 用 `{taskId, tenantId, workerId, success(bool), message, outputs, errorCode, resultSummary}`。

Python dispatcher `dispatcher.py` L291-313 REPORT body:

```python
# _report_success
body = {"tenantId", "workerId", "status": "SUCCESS"}
# _report_failure
body = {"tenantId", "workerId", "status": "FAILED", "errorMessage": reason}
```

**致命错配**:

1. Python 用 `status: "SUCCESS" / "FAILED"` 字符串字段 → 平台 `TaskExecutionReportDto.success` 是 `boolean`,Jackson 反序列化时 `status` 被 `@JsonIgnoreProperties(ignoreUnknown=true)` 静默丢弃,`success` 字段默认 `false` → **每条 Python worker 上报都会被平台判为失败**。
2. Python 失败用 `errorMessage`(已废弃字段名,Java DTO 没有),正确字段名为 `resultSummary` + `errorCode`(`ReportRequest.java` javadoc 明确警告:"已废 `errorClass` / `errorMessage`")。
3. Python body 缺 `taskId`(平台 controller `@RequestBody` 反序列化时 path variable 没回填到 body 字段)、`code`、`outputs`、`failureClass`、`verifierFailures`、`highWaterMarkOut`、`partitionInvocationId`。

**这条 P0 等同于"Python SDK 跑不通生产任务"** — 即使 HTTP 200,job_task 也写 FAILED。`SdkWireContractTest` 只测了 Java SDK record 反序列化到平台 DTO,没扫 Python body 形状。

### 1.3 idempotency-key 形态(**P0-3**)

`docs/sdk/wire-protocol.md §A` 规定每次写操作单独生成 key,4xx 不重放、5xx 重放同 key。Java 实现:

```java
// TaskDispatcher.java L248, L297
String idemClaim  = BatchPlatformClient.newIdempotencyKey();   // "sdk-<uuid>"
String idemReport = BatchPlatformClient.newIdempotencyKey();   // 新 uuid
```

Python:

```python
# dispatcher.py L250
idempotency_key = msg.get("idempotencyKey") or f"claim-{task_id}"
# L298, L310
await self._http.report(task_id, f"report-{task_id}", body)
```

问题:

- Python REPORT 用 `report-{task_id}` 是固定值 → 同一 task 多次 report(handler 重试 / lease 续投递)走的是同一个 idempotency key,平台幂等存储里看到的就是"上次结果",新 outcome 永不落地。
- Python CLAIM 若上游 kafka msg 不带 `idempotencyKey` 字段(平台目前不下发),退化成 `claim-{task_id}` 固定值 — 同样不可重试;且与 Java `sdk-<uuid>` 命名空间冲突,平台运维难以按前缀区分。
- 上层 `claim_max_5xx_retries=3` 路径(Java)实际是 SDK 5xx 退避自动重试 → 同 key 重发(平台幂等;OK)。Python 没实现这个路径(`with_retry` 也是同 key 重发,但 key 是函数级捕获,5xx 重试也确实复用同 key),但仍正确。

修法:Python 应在 `_process` 入口生成两个独立 UUID(`f"sdk-py-{uuid4()}"`),并把 key 全程透传到 `with_retry` 内部以保证 5xx 重试同 key。

### 1.4 `/internal/workers/{code}/status` GET vs POST(**P0-2**)

`batch-orchestrator/.../WorkerController.java` L54:`@PostMapping("/{workerCode}/status")` + body=WorkerHeartbeatDto,语义是"运维更新 worker 状态"(`updateStatus`)。openapi L110 同 POST,operationId `updateWorkerStatus`。

Python `_http.py` L110:`async def get_status(...) GET ...` — 实际平台没有 GET 端点,会 404 / 405。

判定:Python "想做" 的查询接口在 orch 不存在;应改名 `update_status` + 改 POST + body=WorkerHeartbeatDto,或干脆从 Python SDK 删除(SDK 不该调用 internal-only 运维端点)。注释里写"单 worker 仪表盘数据"也是误导 — 该端点不返回仪表盘,返回的是 `WorkerRegistryEntity`。

### 1.5 retry / backoff / fail-fast 旋钮对齐

| 旋钮 | Java | Python | 备注 |
|---|---|---|---|
| `claimMax5xxRetries` | 默认 3 | **改名为** `retry_max_attempts` 默认 3 | 文档表 L20–22 仍标 `claim_max_5xx_retries` → 文档代码漂移 **P1-1** |
| `claimRetryBaseDelay` | 200ms | `retry_base_delay` 200ms | 同上漂移 |
| `clientErrorFailFastThreshold` | 默认 5 | `client_error_fail_fast_threshold` 默认 5 | ✓ |
| 401/403 立即抛 | `PlatformHttpException.isAuthError()` | `AuthError`(by status 判定) | 行为对齐 ✓ |
| 409 当成功 | `isConflict()` 走正常返回 | `with_retry` L144-148 `counter.reset() return resp` | ✓ |
| 5xx 退避 | `base * 2^attempt` | `base * 2^(attempt-1)` ± 10% jitter | ✓(Java 注释说指数同样) |
| 404 非鉴权 4xx 不计入 fail-fast | `_retry.py` L150-157 实现 | Java 有同等谓词 | ✓ |
| `strictTimingValidation` env 降级开关 | ✓ `BATCH_SDK_STRICT_TIMING=false` | **未实现** | **P1-2**,生产降级时 Python pod 必抛 |

### 1.6 心跳 hint clamp

`HeartbeatScheduler` 双端均按 `[1s, baseline * 10]` 钳制,常量名 + 注释逐字对照:

- Java `HeartbeatScheduler.java` L29-32:`MIN_HINT_MS=1_000L`、`MAX_HINT_MULTIPLIER=10L`。
- Python `_heartbeat.py` L41-42:`MIN_HEARTBEAT_INTERVAL_S=1.0`、`MAX_HEARTBEAT_HINT_MULTIPLIER=10`。

行为一致 ✓。

### 1.7 timing 4 规则 fail-fast

Java `BatchPlatformClientConfig.validateTimings()` 4 条:`hb>=1s / lease>=5s / lease<=hb*3 / http<=hb/2`;违反时 `reportTimingViolation` → `strictTimingValidation=true` 抛 IllegalStateException,`false` 仅 WARN。

Python `client/config.py._validate_timings` 同 4 条规则、相同错误文案(前缀 `BatchPlatformClient config invalid:`),**但**没有 strict 开关,违例必抛 ValueError → 无法对齐 Java 端"降级窗口"。**P1-2**

### 1.8 Kafka 配置必填 vs 可选

| 字段 | Java | Python |
|---|---|---|
| kafkaBootstrap | `@NonNull` 必填 | `kafka_bootstrap: str \| None = None` |
| kafkaTopicPattern | `@NonNull` 必填 | 可选 |
| kafkaGroupId | `@NonNull` 必填 | 可选 |

Python 选择是"scheduler-only 模式"(`client.py` L249-256 注释:无 kafka_factory 时仅 HTTP heartbeat);Java 强制 Kafka 三元组。两端定位不同,但 ADR-035 没明文说 Python 可降级。

→ **P2 决策**:文档需把"Python SDK 可 scheduler-only" 升到 ADR-035 §11,否则容易误用为正常生产路径(无 Kafka → 没有任务派单)。

---

## 2. 共享常量 / openapi 对照

### 2.1 `docs/api/sdk-shared-constants.yaml`

| Key | Java source | YAML | Python `constants.py` | 校验 |
|---|---|---|---|---|
| schema_versions_supported | `TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS` = ("v1","v2") | ✓ | `SCHEMA_VERSIONS_SUPPORTED` tuple ✓ | `SharedConstantsParityTest` (Java) + tests/test_shared_constants_parity.py (Python) |
| worker_runtime_states | NORMAL/DEGRADED/PAUSED/DRAINING | ✓ | frozenset ✓ | ✓ |
| sensitive_keywords | 13 项 | ✓ | frozenset 13 项 ✓ | ✓ |
| task_statuses | CREATED/READY/RUNNING/SUCCESS/FAILED/CANCELLED/TERMINATED | ✓ | tuple ✓ | ✓(但 tuple vs set 在 parity test 里没断言顺序,P1-5 风险) |
| atomic_error_codes | `[]` 占位 | `[]` 占位 | 未导出 | OK,有 `optional_keys` 回退 |

### 2.2 `docs/api/orchestrator-internal.openapi.yaml` vs SDK wire DTO

抽样核对 10 处 `$ref: '#/components/schemas/WorkerHeartbeatDto'` / `TaskExecutionReportDto` / `TaskClaimRequest`,均存在且 Java SDK record 字段名匹配。openapi 第 110 行确认 `/status` 是 POST,Python SDK 实现错。

### 2.3 fixtures(`docs/api/sdk-contract-fixtures/`)12 件

涵盖 register/heartbeat/claim/report/renew/kafka-pause/stop-with-timeout,符合 Phase 0 §2.1。问题:**当前只被 Java `SdkWireContractTest` 消费**,Python `tests/` 目录没有 fixture-driven 端到端反序列化测试 → Python 字段漂移在 CI 中 silent。

---

## 3. 数据完整性

### 3.1 Flyway V1–V165 ALTER → archive 镜像

最近 7 个 migration 抽样:

| 文件 | 改 batch.* | archive 镜像同 PR | 结论 |
|---|---|---|---|
| V157 worker_registry_is_self_hosted | ALTER batch.worker_registry | 无 | **设计上不归档**(运行时元数据),应在 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 注释明示 — **P1-4** |
| V160 job_task_effective_parameters | ALTER | ✓(archive 2 处) | OK |
| V161 job_task_heartbeat_details_cancel | ALTER | ✓ | OK |
| V162 task_timeout_seconds | ALTER batch.job_task + batch.task_timeout | ✓ | OK |
| V163 worker_registry_fingerprint | ALTER batch.worker_registry | 无 | 同 V157 理由 |
| V164 create_pipeline_progress | CREATE + UNIQUE(tenant_id, …) | ✓ archive 镜像 + ARCHIVED_TABLES L73 | OK |
| V165 create_atomic_task_config | CREATE + UNIQUE(tenant_id,…) | ✓ + ARCHIVED_TABLES L75 | OK |

`ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 已列 22 张(L42-76),启动期 fail-fast 守护到位。

### 3.2 outbox 三表分工

CLAUDE.md 规定三表分工:

- `outbox_event` → 通用业务事件(orchestrator 主用,`OutboxEventMapper.java`)
- `event_outbox_retry` → 投递失败退避重试
- `trigger_outbox_event` → trigger fire → orchestrator launch 调度事件

代码扫描确认:
- `batch-trigger` 仅引 `trigger_outbox_event`(`TriggerOutboxEventMapper`、`TriggerOutboxRelay`、`TriggerOutboxDomainEventPublisher`),无串用 ✓
- `batch-orchestrator` 引 `outbox_event` + `event_outbox_retry`,无引 `trigger_outbox_event` 写入路径(只在 `ArchiveSchemaDriftCheck` 登记) ✓
- 没发现第 4 张同义表新增

### 3.3 多租 UNIQUE 守护

- V159 custom_task_type_registry:`UNIQUE (tenant_id, task_type_code)` ✓
- V164 pipeline_progress:`UNIQUE (tenant_id, pipeline_instance_id, stage)` ✓
- V165 atomic_task_config:`UNIQUE (tenant_id, task_type, name)` + 索引 `(tenant_id, task_type)` ✓

`MapperXmlTenantGuardArchTest` 在 orchestrator 与 console-api 双覆盖,白名单 6 个 mapper(EventDeliveryLog/EventOutboxRetry/RetrySchedule/OutboxEvent/BatchDayWaitingLaunch/FileGovernance)— 都是合规的 ROLE_ADMIN 跨租运维入口。RetryScheduleMapper 是否仍应在白名单需复核(**P2-4**)。

### 3.4 跨服务事务边界(trigger → orch → worker)

- trigger:fire → `trigger_outbox_event` 同事务(`TriggerOutboxDomainEventPublisher`)。
- orch:状态 update + `outbox_event` insert 同事务(`OutboxDomainEventPublisher.java` L45 `outboxEventMapper.insert(entity)`,被 `@Transactional` 服务调用)。CLAUDE.md "outbox_event 写入必须与任务状态同事务" 命中。
- worker:不直接写 `job_instance` / `workflow_run`(SDK 通过 REPORT 上送,orch 端写入数据库)。`DefaultCompensationService` 用 `REQUIRES_NEW` 拆 INSERT 命令 / 标 FAILED 独立提交,目的是"handler 失败也留住命令行"— 与边界硬约束兼容。

未发现 worker 端直接 UPDATE job_instance 的违规(grep `Mapper` 调用)。

### 3.5 soft-delete 字段查询过滤

7 处 mapper XML 含 `is_deleted` / `deleted_at`,样本(FileChannelConfig / ConsoleDashboardQuery / AlertRoutingConfig)均带 `is_deleted = false` 过滤;V145 已加 partial index。整体 OK,但项目 soft-delete 域只在 console-api 配置类用,业务核心(job_instance / workflow_run / pipeline_instance)不做 soft-delete(走 archive 冷表)— 边界自洽。

### 3.6 时区 / 编码扫描

`grep -rn "ZoneId\.systemDefault\|LocalDateTime\.now\|LocalDate\.now" --include="*.java" <10 模块> | grep -v /test/`:

仅 6 行命中,**全部位于 `BatchTimezoneProperties` / `BatchTimezoneProvider` / `BatchDateTimeSupport`**(守护类自述用),业务代码 0 命中 ✓。

`grep 'Charset.forName\|"UTF-8"'`:仅 `EncodingUtils.java`(守护工具,显式 `Charset.forName(raw)` 解析租户配置)+ `BatchI18nAutoConfiguration.java` 注释 + `ConfigPackageExcelWorkbookWriter.java` 中两处 `"UTF-8"` 是**生成给租户看的 Excel 注释列默认值**(非代码字符集),非违规。✓

---

## 4. 详细 finding 列表

### P0(必修)

#### P0-1 Python REPORT body 字段错,平台读不到 success/失败信息

**文件**:`batch-worker-sdk-python/src/batch_worker_sdk/dispatcher/dispatcher.py` L291-313

**证据**:Python 用 `status: "SUCCESS"` + `errorMessage: ...`,平台 `TaskExecutionReportDto` 字段是 `success: boolean` + `resultSummary` + `errorCode`,`@JsonIgnoreProperties(ignoreUnknown=true)` 静默丢弃 → `success` 永远 `false`、`errorMessage` 永远拿不到。

**影响**:Python SDK 跑出的所有任务在 orch 视角都是 FAILED + 无错误细节。`SdkWireContractTest` 没扫 Python wire,CI silent。

**修法**:Python `_report_success`/`_report_failure` 改用 `success=True/False` + `resultSummary` + `errorCode`,补 `taskId / code / outputs / failureClass / verifierFailures / highWaterMarkOut`,补 traceId/partitionInvocationId 透传。**强烈建议**新增 `tests/test_report_wire_contract.py` 用 `docs/api/sdk-contract-fixtures/09-*.json` driven。

#### P0-2 `/internal/workers/{code}/status` Python 用 GET,平台是 POST

**文件**:`batch-worker-sdk-python/src/batch_worker_sdk/internal/_http.py` L110 `get_status`

**证据**:`WorkerController.java` L54 `@PostMapping("/{workerCode}/status")` + body=WorkerHeartbeatDto;openapi 同。Python `_get_json`(GET 无 body)→ 405/404。

**影响**:Python SDK 调 `get_status` 会失败;若运维以为该方法可用作健康自查,会误报。

**修法**:删除该方法(SDK 不该调 internal-only 端点),或改名 `update_status` + 改 POST + 补 body。同时 `_http.py` 顶部端点清单注释要订正。

#### P0-3 Python idempotency-key 固定为 `claim-{taskId}` / `report-{taskId}`

**文件**:`batch-worker-sdk-python/src/batch_worker_sdk/dispatcher/dispatcher.py` L250, L298, L310

**证据**:Java `BatchPlatformClient.newIdempotencyKey()` = `"sdk-" + UUID.randomUUID()`,每次 claim/report 独立 key,符合 wire-protocol §A;Python 固定 `claim-{taskId}` / `report-{taskId}`。

**影响**:
1. 同一 task 多次 report(handler 重试 / 上次 5xx 后再 dispatch)走同 idempotency-key,平台返回上次结果 → 新 outcome 不落地。
2. 多 worker 实例同时处理同一 task(竞争)时,使用相同的 `claim-{taskId}` key 也会绕过 SDK 端去重指纹。

**修法**:`idempotency_key = f"sdk-py-{uuid.uuid4()}"`;`_process` 入口生成两个独立 key 透传到 claim/report。

#### P0-4 Python 心跳 body 缺 6 个字段 → worker_registry 运维列被静默清空

**文件**:`batch-worker-sdk-python/src/batch_worker_sdk/scheduler/_heartbeat.py` L142-148

**证据**:Java `HeartbeatRequest` record 10 字段(含 `workerGroup`, `hostName`, `hostIp`, `processId`, `capabilityTags`, `currentLoad`),平台心跳路径共用 `WorkerHeartbeatDto`,会把 register 时建立的运维元数据列被空心跳覆盖。Python tick 只发 5 字段。

**影响**:Python worker register 后,心跳一次 worker_registry 表里的 hostName/hostIp/processId/workerGroup/capabilityTags 全被刷成 NULL → 控制台"我的 Worker"看不到 IP/主机/能力标签,运维诊断瞎。

**修法**:`tick()._build_heartbeat_body` 与 `_build_register_body` 共用一个字段构造函数,heartbeatAt + currentLoad 这两个是 tick 内每次刷新,其余从 config / handlers 复用。

### P1(应修)

#### P1-1 Python config 文档表 vs 实际字段名漂移

`batch-worker-sdk-python/src/batch_worker_sdk/client/config.py` L20-22 文档表写 `claim_max_5xx_retries / claim_retry_base_delay`,实际 L76-77 字段名是 `retry_max_attempts / retry_base_delay`。注释误导新接入者。

#### P1-2 Python 缺 `strictTimingValidation` 开关

Java 提供 `BATCH_SDK_STRICT_TIMING=false` 让运维降级窗口短期跳过 timing 4 规则,Python 没实现。生产场景 Python pod 配置稍偏(如 hb=15s 但运维想要先上线再修)无法降级,持续重启。

#### P1-3 Python register 已上报 buildId/sdkVersion,但 heartbeat 不上报刷新

同 P0-4 衍生。Java 在每次 heartbeat 也带 buildId/sdkVersion(共用 WorkerHeartbeatDto),Python 只在 register 一次性上报。租户滚动升级期间(同 workerCode 换 buildId),平台看不到新版本指纹。

#### P1-4 `worker_registry` 永不归档应在 `ArchiveSchemaDriftCheck` 注释明示

V157/V163 都改了 `batch.worker_registry`,但 `ARCHIVED_TABLES` 不含。这个设计合理(运行时注册元数据,不存历史),但代码没注释说明,新人评审 V165+ 时容易误以为漏补 archive migration。

**修法**:`ArchiveSchemaDriftCheck.java` 顶部注释或单独 NOT_ARCHIVED_TABLES list 列 `worker_registry / api_key / shedlock / step_registry / biz_table_schema` 等并写"运行态/系统表不归档" 理由。

#### P1-5 `task_statuses` parity 弱:tuple vs set 未断言顺序

`docs/api/sdk-shared-constants.yaml` 列表序 + Java enum 序 + Python tuple 序需一致(否则未来若加 enum 项位置错放在中间,会破坏旧版本反序列化默认值)。当前 `SharedConstantsParityTest` 只断言集合等价,顺序漂移过不了测试。

#### P1-6 `/internal/tasks/leases/renew-batch` 批量续约端点 SDK 未消费

平台已暴露批量续约端点(orchestrator `@PostMapping("/leases/renew-batch")`),用于减少 N 个 in-flight task 时的 N 个 RTT。Java + Python SDK 都还是 per-task `renew`,N 大时延迟过 lease TTL 概率上升。

### P2(可选)

- **P2-1** `PlatformHttpClient._decode_body` L214 把数组响应包成 `{"_array": ...}` 是防御性回退,Java 没有对应分支。当前所有 stable 端点都返回 object,本路径死代码,建议删或抛 `PlatformError`。
- **P2-2** Python 缺 `force-offline/takeover/warmup/claimed-tasks` 4 个 internal-only 端点,合理(运维用),但 `_http.py` 顶部端点清单注释应明示"刻意不接入"。
- **P2-3** Java 心跳完整字段集合 Python 心跳缺,见 P0-4。
- **P2-4** `MapperXmlTenantGuardArchTest` 白名单 `RetryScheduleMapper`、`EventOutboxRetryMapper` 是否仍需 `<if tenantId>` 条件守护待复核;若已统一改 ROLE_ADMIN 显式参数化路径可下架。

---

## 5. 已验证 OK 项

| 项 | 证据 |
|---|---|
| Flyway V160–V165 archive 镜像同 PR | `grep -c "archive\." V16x__*.sql` ≥ 2 行 |
| `ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 覆盖 22 表(含 V159/V164/V165) | `ArchiveSchemaDriftCheck.java` L42-76 |
| V164/V165 多租 UNIQUE 守护 | `UNIQUE (tenant_id, ...)` 明文 |
| outbox 三表分工无串用 | trigger 模块仅 `trigger_outbox_event`,orchestrator 仅 `outbox_event` / `event_outbox_retry` |
| 时区违规 0 业务命中 | grep 6 行均在守护类 |
| 编码违规 0 业务命中 | grep 命中均在 `EncodingUtils` / Excel 注释默认值 |
| `MapperXmlTenantGuardArchTest` orch + console-api 双覆盖 | 见两文件 |
| `SdkWireContractTest` + `SharedConstantsParityTest` 锁定 Java/平台契约 | 测试存在 |

---

## 6. 整改建议(按优先级)

### Sprint-1(本周)
1. **P0-1 + P0-2 + P0-3 + P0-4 一并修**(都在 Python SDK,可单 PR `fix(sdk-python): align wire contract with orchestrator/orchestrator-internal openapi`)。
2. 新增 `batch-worker-sdk-python/tests/contract/test_wire_fixtures.py`,driven by `docs/api/sdk-contract-fixtures/*.json`,把 register/heartbeat/claim/report/renew 5 个 fixture 都跑反序列化 + 字段断言,堵漏 CI silent。
3. `_http.py` 顶部端点表 / endpoint method 校正注释。

### Sprint-2
4. **P1-1** 修文档表。
5. **P1-2** 引入 `strict_timing_validation: bool = True` + env `BATCH_SDK_STRICT_TIMING`。
6. **P1-3** Python heartbeat 字段补齐(P0-4 的延伸,可合并 Sprint-1)。
7. **P1-5** `SharedConstantsParityTest` 升级为顺序断言。

### 后续 follow-up
8. **P1-4** archive 守护类补 NOT_ARCHIVED_TABLES 注释。
9. **P1-6** 批量续约 SDK 端切换(Java + Python 同步)— 性能优化,独立 ADR。
10. **P2-4** `MapperXmlTenantGuardArchTest` 白名单复核。

---

## 7. 补充证据片段

### 7.1 Java 心跳 body 真实字段集(对照基线)

`batch-worker-sdk/src/main/java/com/example/batch/sdk/scheduler/HeartbeatScheduler.java`(经 `BatchPlatformClient.buildHeartbeatBody` 构造)与 Python `_heartbeat.py` L142-148 形态差异表:

| 字段 | Java | Python | 落地结果 |
|---|---|---|---|
| tenantId | ✓ | ✓ | OK |
| workerCode | ✓ | ✓ | OK |
| workerGroup | ✓("sdk-self-hosted") | ✗ | `worker_registry.worker_group` 被覆盖 NULL |
| status | ✓ | ✓("RUNNING") | OK |
| hostName | ✓ | ✗ | 同上 NULL |
| hostIp | ✓ | ✗ | 同上 NULL |
| processId | ✓ | ✗ | 同上 NULL |
| heartbeatAt | ✓ Instant | ✓ ISO-Z | OK |
| capabilityTags | ✓ sorted | ✗ | 平台能力路由失效 |
| currentLoad | ✓ in-flight count | ✓ in-flight count | OK |
| buildId | ✓ | ✗ | 滚动升级期看不到新 build |
| sdkVersion | ✓ | ✗ | 同上 |

→ Python 心跳实际"等于把 worker 在表里抹掉运维侧信息"。修复必须配合一次 register 同形 body 重发,否则字段空格期 worker 不可见。

### 7.2 idempotency-key 形态对照(Java vs Python)

```java
// Java BatchPlatformClient.java L252
public static String newIdempotencyKey() { return "sdk-" + UUID.randomUUID(); }
// 使用点(TaskDispatcher.java L248/L297)
String idemClaim  = BatchPlatformClient.newIdempotencyKey();
String idemReport = BatchPlatformClient.newIdempotencyKey();
```

```python
# Python dispatcher.py L250/L298/L310
idempotency_key = msg.get("idempotencyKey") or f"claim-{task_id}"
await self._http.report(task_id, f"report-{task_id}", body)
```

Java key namespace `sdk-<uuid>` 平台运维 grep 可定位;Python `claim-/report-<taskId>` 与"用 task_id 直接组装"语义碰撞,平台幂等存储看到的就是固定 key 上次回放 → 平台日志里运维很难分辨"哪个尝试是哪次"。

### 7.3 archive 镜像 Flyway 抽样

```bash
# V160 job_task_effective_parameters
$ grep -c "archive\." db/migration/V160__job_task_effective_parameters.sql
2
# V164 create_pipeline_progress
$ grep -c "archive\." db/migration/V164__create_pipeline_progress.sql
4
# V157/V163 worker_registry — 期望 0(运行态表不归档)
$ grep -c "archive\." db/migration/V157__worker_registry_is_self_hosted.sql
0
$ grep -c "archive\." db/migration/V163__worker_registry_fingerprint.sql
0
```

`ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 不含 `worker_registry`,运行期不会拦截 → 设计自洽,但缺一句"why not"注释。

### 7.4 outbox 三表分工 grep 结果

```bash
$ grep -rln "trigger_outbox_event" --include="*.java" batch-trigger batch-orchestrator
batch-trigger/...{TriggerOutboxEventMapper, TriggerOutboxRelay, DefaultTriggerService, TriggerOutboxDomainEventPublisher}
batch-orchestrator/.../ArchiveSchemaDriftCheck.java        # 仅归档守护登记,无写入
```

`batch-orchestrator` 没引 `trigger_outbox_event` 的写入路径,反向也成立(trigger 不引 `outbox_event`)。分工 ✓。

### 7.5 跨服务事务边界标注

CLAUDE.md "outbox_event 写入必须与任务状态同事务":

- `batch-orchestrator/.../OutboxDomainEventPublisher.java` L45 `outboxEventMapper.insert(entity)` 在 `@Transactional` Service 调用栈内,与 `job_instance` / `workflow_run` 状态 UPDATE 同事务 ✓
- `DefaultCompensationService` 用 `REQUIRES_NEW` 拆 INSERT 命令 + 标 FAILED → 独立提交是为了"handler 失败也留住命令行"(L113-116 注释明确),CLAUDE.md §4 规则 4 豁免:"Propagation.NEVER 之外的非默认传播 禁" — `REQUIRES_NEW` 算违规但 javadoc 写了理由,需架构组登记 ADR 例外清单(本次不展开)。

### 7.6 时区 / 编码 grep 全量结果

```
ZoneId.systemDefault / LocalDateTime.now / LocalDate.now :
  6 行,全部在守护类自述
    BatchTimezoneProperties.java (3 行,javadoc)
    BatchTimezoneProvider.java (1 行,javadoc)
    BatchDateTimeSupport.java (2 行,javadoc 列举禁用 API)

Charset.forName / "UTF-8":
  EncodingUtils.java L41/L53/L65 — 工具自身要解析任意 charset 字符串
  BatchI18nAutoConfiguration.java L43 — 注释自述用 StandardCharsets
  ConfigPackageExcelWorkbookWriter.java L1113/L1114 — 给租户的 Excel 注释列默认值(字符串字面量,非代码字符集)
```

业务代码 0 命中 ✓。

---

## 8. 关键文件清单(整改入口)

- `batch-worker-sdk-python/src/batch_worker_sdk/dispatcher/dispatcher.py`(P0-1/P0-3)
- `batch-worker-sdk-python/src/batch_worker_sdk/internal/_http.py`(P0-2 GET→POST + 端点表注释)
- `batch-worker-sdk-python/src/batch_worker_sdk/scheduler/_heartbeat.py`(P0-4 心跳字段)
- `batch-worker-sdk-python/src/batch_worker_sdk/client/client.py`(P1-3 抽 `_build_worker_body` 复用)
- `batch-worker-sdk-python/src/batch_worker_sdk/client/config.py`(P1-1 文档表 + P1-2 strict 开关)
- `batch-worker-sdk-python/tests/contract/`(新增 fixture-driven 测试目录)
- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/archive/ArchiveSchemaDriftCheck.java`(P1-4 NOT_ARCHIVED 注释)
- `batch-worker-sdk/src/test/java/com/example/batch/sdk/contract/SharedConstantsParityTest.java`(P1-5 顺序断言)

---

## 9. 范围边界声明

- 本扫描覆盖 Java/Python SDK wire/行为 + Flyway + archive + 多租 + 跨服务事务 + 时区/编码静态扫,**未**深入:
  - SDK 内 atomic handlers(`shell/sql/http/storedProc`)语义对照(ADR-029 范围,另写)
  - Kafka consumer rebalance / 背压(`_kafka.py` vs `KafkaTaskConsumer.java`)
  - 测试覆盖率与 CI gating 真实矩阵
- 数据完整性边界:CLAUDE.md "Pipeline vs Workflow vs Job" 分界已自带守护测试,本次不复扫;仅核对了"worker 不直接写状态"约束。
- 不涉及前端/部署/observability。

— end —
