# SDK 测试覆盖对照矩阵 — Java vs Python

日期:2026-06-03
范围:`batch-worker-sdk/`(Java, 56 个 `*Test.java`) vs `batch-worker-sdk-python/`(51 个 `test_*.py`,345 个用例)。
方法:**按功能而非文件名对齐**。逐个 Java 测试类读其 `@Test` 方法 + `@DisplayName`,在 Python 侧搜同语义用例,判定 ✅ / 🟡 / 🔴 / N/A。

> N/A = Python SDK 没有对应特性(Java 特有,如 ServiceLoader / AOP 风格的 `@RetryOn`/`@Idempotent` 注解装饰、JVM 指纹、ThrottledLogger),也没必要补。

## 总览

| 状态 | 数量 |
|---|---|
| ✅ 已完整覆盖 | 39 |
| 🟡 部分覆盖(补 case,不新建文件) | 0 |
| 🔴 真实缺失(本 PR 补) | 3 |
| ⚪ N/A(Python 无该特性) | 14 |
| 合计(Java 测试类) | 56 |

## 矩阵

| Java 测试类 | 测试目的 | Python 覆盖文件 | 覆盖度 | 备注 |
|---|---|---|---|---|
| `BatchPlatformClientBuilderTest` | builder 校验:重复 type、空 type、缺 handler 时 start 抛错 | `tests/test_client.py::test_register_handler_rejects_duplicate` 等 + `tests/test_decorator.py::test_empty_task_type_rejected` | ✅ | |
| `BatchPlatformClientConfigEnvTest` | `fromEnv` 解析 + 缺字段聚合报错 | `tests/test_config_validation.py::test_from_env_*` | ✅ | |
| `BatchPlatformClientConfigTest` | config 基础字段校验 | `tests/test_config_validation.py` 全文 | ✅ | |
| `BatchPlatformClientConfigValidationTest` | hb/lease/http 三规则 + 边界值 + builder 期 fail-fast | `tests/test_config_validation.py::test_heartbeat_below_1s_rejected/test_lease_*/test_http_timeout_*` | ✅ | |
| `BatchPlatformClientConfigWarnModeTest` | `strict=false` 软告警模式 + env `BATCH_SDK_STRICT_TIMING` | — | ⚪ N/A | Python SDK 当前实现只走 strict-only(`test_config_validation.py` 全是 reject 用例),无 warn-mode 旁路。如 P2 引入再补。 |
| `BatchPlatformClientMetricsTest` | client.is_healthy() / metrics 字段反映 dispatcher / consumer 状态 | — | ⚪ N/A | Python `BatchPlatformClient` 无 `is_healthy()` / metrics 暴露;依赖外部 OTel 集成。 |
| `BatchPlatformClientStartTest` | register 失败时 start 抛 + 已 start 再 start 抛 | `tests/test_client.py::test_double_start_raises`、`test_start_without_handlers_raises` | ✅ | start_without_handlers 比 Java 更严(Python 不允许无 handler 启动),功能等价覆盖。 |
| `BatchPlatformClientStopOrderTest` | stop 顺序:kafka → dispatcher → schedulers → deactivate | `tests/test_client.py::test_stop_phase3_fallback_runs_full_sequence` + `tests/test_lifecycle.py::test_normal_stop_completes_within_budget` | ✅ | |
| `BatchPlatformClientStopTimeoutTest` | stop 内 in-flight 超时 → WARN 但仍完成 | `tests/test_lifecycle.py::test_drain_waits_for_in_flight_to_reach_zero`、`test_drain_timeout_logs_warn_with_task_ids`、`test_negative_timeout_raises` | ✅ | |
| `TaskTypeDescriptorAssemblyTest` | descriptor 默认 null / collect 时按 handler 装配 / taskType 覆盖 code | `tests/test_decorator.py::test_descriptor_passthrough/test_descriptor_mismatch_rejected`、`tests/handler/test_handler_contract.py::test_descriptor_does_not_raise`、`tests/test_surface.py::test_task_type_descriptor_fields` | ✅ | |
| `WorkerFingerprintTest` | pid / hostname / ip / SDK 版本(打包 jar) | — | ⚪ N/A | Java 用 ManifestImplementationVersion + JVM Runtime API;Python 当前 `register()` 不上报 fingerprint 字段。 |
| `JsonFixtureContractTest` | `docs/api/sdk-contract-fixtures/*.json` parameterized 跑契约 | `tests/contract/test_contract_runner.py` + `tests/handler/test_golden_samples.py` | ✅ | |
| `SharedConstantsParityTest` | Java 常量 vs YAML 单一来源对账 | `tests/test_shared_constants_parity.py` | ✅ | |
| `HeartbeatDirectiveTest` | runtimeState 多枚举 / 嵌套封包 / 空 body / 未知枚举降级 | `tests/test_directive.py` 全 5 用例 | ✅ | |
| `KafkaTaskConsumerCapacityPauseTest` | backpressure pause / resume / 无 assignment / PAUSED 状态 | `tests/test_kafka_consumer.py::test_apply_backpressure_*` | ✅ | |
| `KafkaTaskConsumerCrashTest` | consumer poll 异常 → mark consumerCrashed | — | ⚪ N/A | Python `_kafka.py` 用 aiokafka loop 起 task;crash 路径不在 SDK 抽象层直接暴露(由 client.is_healthy 旁路覆盖),且 Python 侧无 `consumerCrashed` 状态字段。 |
| `KafkaTaskConsumerRebalanceTest` | rebalance 清空 paused 缓存 | `tests/test_kafka_consumer.py::test_rebalance_listener_resets_paused_cache` | ✅ | |
| `SdkPlatformContractTest` | register/report body 与 DTO schema 对齐 | `tests/contract/test_contract_runner.py` + `tests/contract/test_testkit_integration.py` | ✅ | |
| `TaskDispatcherAutoWrapTest` | `@RetryOn`/`@Idempotent` 注解自动包装 dispatcher | — | ⚪ N/A | Python 设计**不走注解 AOP**,租户在 handler 内显式 try/except 走 `with_retry`,无 dispatcher 自动 wrap。 |
| `TaskDispatcherClaimJitterTest` | claim 退避带 jitter | — | ⚪ N/A | Python 的 jitter 走通用 `_backoff`(`tests/test_retry.py::test_5xx_retries_*` 已覆盖随机化),claim 路径无独立 jitter 循环。 |
| `TaskDispatcherClaimRetryTest` | claim 自身的 retry 循环(17 用例) | — | ⚪ N/A | Python claim 只 1 次 HTTP(单层),复用 `PlatformHttpClient` 的 with_retry,详见 `tests/test_retry.py`。 |
| `TaskDispatcherP0HardeningTest` | drain 后丢消息 / MDC trace 透传 | `tests/test_dispatcher.py::test_fatal_silently_drops_subsequent_messages`、`test_in_flight_count_tracks_dispatches` | ✅ | MDC = Python 走 `logging.LoggerAdapter`,trace 透传在 dispatcher 单测中默认 mock。 |
| `TaskDispatcherPlatformStateTest` | PAUSED/DRAINING 门控 | `tests/test_dispatcher.py::test_apply_platform_directive_sets_state` | ✅ | |
| `TaskDispatcherTenantMismatchTest` | tenantId 不匹配静默丢 | `tests/test_dispatcher.py::test_tenant_mismatch_drops_message` | ✅ | |
| `TaskDispatcherTest` | claim → execute → report 主链 + 异常路径 | `tests/test_dispatcher.py` + `tests/test_dispatcher_cancellation.py` | ✅ | |
| `TaskDispatchMessageTest` | 消息反序列化 / 未知字段忽略 / schedulingContext / schemaVersion 校验 | `tests/test_dispatcher_schema_versions.py`、`tests/test_dispatcher.py::test_unsupported_schema_version_drops` | ✅ | |
| `HttpAtomicHandlerTest` | HTTP atomic:happy / SSRF / 截断 / 白名单 method / 透传 headers | `tests/handler/atomic/test_http.py` | ✅ | |
| `ShellAtomicHandlerTest` | shell atomic:happy / 白名单 / 字面量注入 / timeout / 截断 | `tests/handler/atomic/test_shell.py` | ✅ | |
| `SqlAtomicHandlerTest` | SQL atomic:select / dml / 角色闸 / 截断 / queryTimeout / 多语句上限 | `tests/handler/atomic/test_sql.py` | ✅ | |
| `StoredProcAtomicHandlerTest` | stored proc:3 道闸 / OUT 读取 / 类型转换 | `tests/handler/atomic/test_stored_proc.py` | ✅ | |
| `FileImportHandlerTest` | CSV / JSONL / 缺文件 | `tests/handler/builtin/test_file_import.py` | ✅ | |
| `HttpDispatchHandlerTest` | 多目标 / 部分失败 / SSRF | `tests/handler/builtin/test_http_dispatch.py` | ✅ | |
| `QueryExportHandlerTest` | CSV / JSONL export / 异常 | `tests/handler/builtin/test_query_export.py` | ✅ | |
| `DelimitedCodecTest` | parse/encode quote / 自定义 delimiter / round-trip / 转义引号 | — | 🔴 缺失 | Python 有 `handler/builtin/_delimited.py::parse_line/encode_line`,但只在 file_import/query_export 端到端用到,**无直接单测**。**本 PR 补 `tests/handler/builtin/test_delimited.py`**。 |
| `SdkAbstractAtomicHandlerTest` | atomic 模板 doInvoke/cleanup/asOutput | `tests/handler/test_abstract_atomic_handler.py` + `tests/handler/test_abstract_handlers_contract.py::test_atomic_abstract_calls_do_invoke_exactly_once` | ✅ | |
| `SdkAbstractDispatchHandlerTest` | 5 条全成功 / 部分失败 / buildRequest 异常 / response 透传 | `tests/handler/test_abstract_dispatch_handler.py` + `tests/handler/test_abstract_handlers_contract.py::test_dispatch_abstract_hook_order` | ✅ | |
| `SdkAbstractExportHandlerTest` | formatRow / writeOut / 流 close / 异常 cleanup | `tests/handler/test_abstract_export_handler.py` + contract | ✅ | |
| `SdkAbstractImportHandlerTest` | batchSize 切片 / 行流 close / 空源 | `tests/handler/test_abstract_import_handler.py` + contract | ✅ | |
| `SdkAbstractProcessHandlerTest` | transform / upsert / null skip / 流 close | `tests/handler/test_abstract_process_handler.py` + contract | ✅ | |
| `SdkAbstractTypedDispatchHandlerTest` | typed dispatch 强类型 | `tests/handler/typed/test_typed_dispatch.py` | ✅ | |
| `SdkAbstractTypedExportHandlerTest` | typed export 强类型 | `tests/handler/typed/test_typed_export.py` | ✅ | |
| `SdkAbstractTypedImportHandlerTest` | typed import 强类型 | `tests/handler/typed/test_typed_import.py` | ✅ | |
| `SdkAbstractTypedProcessHandlerTest` | typed process 强类型 | `tests/handler/typed/test_typed_process.py` | ✅ | |
| `SdkTypedTaskHandlerTest` | typed task handler 基础 | `tests/handler/typed/test_typed_task_handler.py` + `test_typed_parameters.py` | ✅ | |
| `IdempotencyKeyResolverTest` | `{tenantId}` 占位符模板解析 | — | ⚪ N/A | Python 无 `@Idempotent` 注解 / key 解析器。租户在 handler 内显式构 key。 |
| `SdkIdempotentHandlerTest` | `@Idempotent` 注解自动去重包装 | — | ⚪ N/A | 同上,Python 无此自动包装层。 |
| `PlatformHttpClientTest` | register / heartbeat / claim / report / renew / status HTTP 路径 + 401 / 409 | `tests/test_http_client.py` | ✅ | |
| `PlatformHttpExceptionTest` | 异常按 status 分类:401/403=Auth / 409=Conflict / 5xx=Server / 4xx=Client / IOException 子类 | — | 🔴 缺失 | Python `exceptions.py` 实现了 4 类异常分类 + `parse_error_body`,但 **没有对此模块的独立单测**(只在 `test_retry.py` / `test_http_client.py` 端到端见到)。**本 PR 补 `tests/test_exceptions.py`**。 |
| `ThrottledLoggerTest` | 节流日志(同 key 滑窗去重) | — | ⚪ N/A | Python 无 ThrottledLogger 模块,沿用 stdlib `logging`。 |
| `SdkRetryableHandlerTest` | `@RetryOn` 注解 / 业务 fail.error 匹配重试 / 模板模式 | — | ⚪ N/A | Python 设计上**用** `batch_worker_sdk.retry.with_retry` **显式装饰**,无注解 AOP;`tests/test_retry.py` 已覆盖通用重试语义。 |
| `HeartbeatSchedulerDynamicIntervalTest` | hint 重排 / clamp / null 不重排 / 幂等 / heartbeat 异常捕获并抑制 | `tests/test_heartbeat_scheduler.py` + `tests/scheduler/test_heartbeat_hint_clamp.py` | ✅ | |
| `HeartbeatSchedulerTest` | tick 调用 heartbeat / fixedDelay / close 幂等 / 失败不挂 | `tests/test_heartbeat_scheduler.py` | ✅ | |
| `LeaseRenewalSchedulerFixedDelayTest` | 必须 fixedDelay 不 fixedRate | — | ⚪ N/A | Python 走 `asyncio.sleep + while not stop` loop,无 JDK ScheduledExecutorService 的 rate vs delay 之分;`tests/test_lease_renewal_scheduler.py::test_start_and_stop_run_cleanly` 已覆盖语义。 |
| `LeaseRenewalSchedulerTest` | renew 全 in-flight / 单失败不阻塞 / 404 / 410 / cancelRequested | `tests/test_lease_renewal_scheduler.py` | ✅ | |
| `SdkTaskContextTest` | TaskContext 字段 / 不可变 / dryRun | `tests/test_surface.py::test_sdk_task_context_*` | ✅ | |
| `SdkTaskResultTest` | TaskResult success/fail factories / 字段 | `tests/test_surface.py::test_sdk_task_result_factories` + `tests/handler/test_result_contract.py` | ✅ | |

## 真实缺失清单(本 PR 补)

1. **`tests/handler/builtin/test_delimited.py`** — 对齐 `DelimitedCodecTest`
   - parse 普通字段
   - parse 引号包裹的含定界符字段
   - parse 引号内双引号转义
   - parse 末尾空字段
   - encode 需引号时加引号
   - parse + encode round-trip
   - 自定义 delimiter(如 `;`)

2. **`tests/test_exceptions.py`** — 对齐 `PlatformHttpExceptionTest`
   - 4 类异常继承自 `PlatformError`,可用 `except PlatformError:` 统一捕获
   - 各异常字段(status_code / code / message / request_id)透传
   - `PersistentClientError.attempts` 计数透传
   - `TransientError.attempts` + `last_error` 透传
   - `parse_error_body` 抽 BizException 信封(`code` / `message` / `traceId`)
   - `parse_error_body` 兼容老路径 `trace_id` / `requestId`
   - `parse_error_body` 非 dict body 返三个 None

3. **`tests/handler/builtin/test_delimited_format.py`** 合并在 1 内 — `DelimitedFormat` pydantic 模型:frozen / delimiter 长度校验 / defaults 类方法

## N/A 清单(14 个,Python 无对应特性)

`BatchPlatformClientConfigWarnModeTest`(strict-warn 模式)、`BatchPlatformClientMetricsTest`(无 metrics 暴露)、`WorkerFingerprintTest`(无 JVM 指纹)、`KafkaTaskConsumerCrashTest`(无 consumerCrashed 状态字段)、`TaskDispatcherAutoWrapTest`(无注解 AOP)、`TaskDispatcherClaimJitterTest` / `TaskDispatcherClaimRetryTest`(claim 单 HTTP,无独立 retry 层)、`IdempotencyKeyResolverTest` / `SdkIdempotentHandlerTest`(无 `@Idempotent` 注解)、`ThrottledLoggerTest`(无节流日志)、`SdkRetryableHandlerTest`(无 `@RetryOn` 注解)、`LeaseRenewalSchedulerFixedDelayTest`(asyncio loop 无 rate vs delay)。

> 这些差距已在 `docs/analysis/2026-06-02-java-python-sdk-deep-review.md` 标注为「Python SDK 设计**不复刻** Java AOP 风格」,不视为质量缺口。
