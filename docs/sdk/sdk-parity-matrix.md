# 五语言 SDK 对齐矩阵(亲核版)

> **用途**:回答"五语言 SDK 哪些对齐 / 哪些没对齐"。每格都附 `文件:行号` 证据,可复核。
> **配套**:[`byo-sdk-guide.md`](byo-sdk-guide.md)(设计与定位)、[`byo-conformance-contract.md`](byo-conformance-contract.md)(契约用例)。
> **核查日期**:2026-06-17(基于当时 `main`)。改动 SDK 协议/能力后请同步本表。

## 0. 怎么读这张表 + 核查方法论(重要)

本表是**逐文件亲核**的结果,不是 grep 汇总。原因:基于单点 `grep` 的快速扫描在这件事上容易系统性误判,根因是——

- **能力实现按语言惯例分散**:薄档(Go/Rust/TS)的**幂等**和 retry 打包在 `resilience.*` 里,**不在** `handler.*`。只 grep `handler.*` 会得出"无幂等"的错误结论。
- **富档模板路径有层级**:Python 类型化模板在 `handler/typed/_typed_*.py`,不在 `handler/_import.py`。查错路径会得出"没接 ADR-037"。

**复核守则**:判定某能力"缺失"前,必须 `grep -rn '<符号>' sdk/<lang>/`(全目录,不限单文件),并打开实现文件确认,不能凭单点 grep 下结论。判定"已落 main"要按 `main` 分支实际内容核对,不看 PR 状态。

## 1. 两档定位(有意,不是缺口)

| 档 | 语言 | 定位 | 含 |
|---|---|---|---|
| 富档 first-party | **Java / Python** | batteries-included | 协议引擎 + ADR-037 + 幂等 + **atomic 执行器**(shell/sql/http/storedproc)+ **typed 模板**(import/export/process/dispatch)+ **builtin handlers** |
| 薄档 BYO | **Go / Rust / TS** | 只稳协议,逻辑租户自带 | 协议引擎 + ADR-037 + 幂等 + 单一 `TaskHandler` 接口;**无** typed 模板 / atomic / builtin(有意) |

## 2. 协议引擎 + 生命周期(五语言**必须**对齐)

| 维度 | Java | Python | Go | Rust | TS |
|---|---|---|---|---|---|
| register 字段齐 | ✅ | ✅ | ✅ `lifecycle.go:137` | ✅ | ✅ |
| Kafka `workerType`(非旧 taskType) | ✅ `dispatcher/TaskDispatchMessage.java:37` `@JsonAlias` 回退 | ✅ `dispatcher.py:337` | ✅ `consumer.go` | ✅ `kafka.rs` | ✅ `consumer.ts` |
| protocolVersion 注册门禁 | ✅ "v2" | ✅ "v2" | ✅ `lifecycle.go:137`(末位 supported) | ✅ register "v2" | ✅ |
| renew 409 → 弃任务/停 handler | ✅ | ✅ | ✅ `scheduler.go:277` | ✅ scheduler | ✅ `scheduler.ts:192` |
| renew `cancelRequested` → 取消 | ✅ | ✅ | ✅ `scheduler.go:214` MarkCancelled | ✅ | ✅ `scheduler.ts:150` |
| deactivate 优雅告别 | ✅ | ✅ | ✅ `lifecycle.go:369` | ✅ `lifecycle.rs:196` | ✅ `transport.ts:61` |
| backpressure pause/resume | ✅ | ✅ `_kafka.py:263` | ✅ `consumer.go` | ✅ | ✅ `consumer.ts` |
| 401/403 fail-fast | ✅ | ✅ | ✅ `decide.go` | ✅ | ✅ `transport.ts:14` |

> **非缺口小项**:Python `dispatcher.py:337` 用 `msg.get("workerType")`,无 `taskType` 回退。BE v2 已不发 `taskType`(`batch-common/.../TaskDispatchMessage.java` 注释),故非正确性缺口;Java 的 `@JsonAlias` 只是额外的向后兼容冗余。

## 3. ADR-037(断点续跑/可靠提交)+ 幂等(五语言齐)

| 能力 | Java | Python | Go | Rust | TS |
|---|---|---|---|---|---|
| P1 SdkCheckpoint + State + InMemory | ✅ `checkpoint/` | ✅ `task/checkpoint.py` | ✅ `client/checkpoint.go` | ✅ `client/checkpoint.rs` | ✅ `client/checkpoint.ts` |
| P2 commit 三合一(save+限流上报+取消) | ✅ `SdkCommitCoordinator` | ✅ `context.py:138` | ✅ `handler.go:154` | ✅ `handler.rs:213`(+`try_commit`) | ✅ `checkpoint.ts:183` |
| P3 SdkTaskStopped → CANCELLED | ✅ | ✅ `exceptions.py:113` | ✅ `checkpoint.go:111` | ✅ `handler.rs:392` map | ✅ `checkpoint.ts:97` |
| typed 模板接 P1-P3(import/export/process) | ✅ `handler/typed/` | ✅ `handler/typed/_typed_*.py` + `_resumable.py` | 〇 无 typed(薄档) | 〇 无 typed(薄档) | 〇 无 typed(薄档) |
| typed dispatch 接 ADR-037 | ❌ 不接(P4 deferred) | ❌ 不接(P4 deferred) | — | — | — |
| **幂等** wrapper + Store + Noop/InMemory + 测试 | ✅ 声明式 `idempotent/`(`@Idempotent`) | ✅ 声明式 `idempotent/`(`@idempotent`) | ✅ `resilience.go:103` `WithIdempotency` | ✅ `resilience.rs:95` `with_idempotency` | ✅ `idempotency.ts:31` `withIdempotency` |

> **幂等风格分两类(都对)**:富档声明式(注解/装饰器,搭注册层);薄档显式包装函数(语言惯例)。语义一致:原子 `tryAcquire/find/record/release` + in-flight 短路 + 失败释放占位。
> **dispatch 不接 ADR-037 是对齐的有意为之**:Java 和 Python 的 dispatch 模板都不接(ADR-037 P4 并行流明确 deferred),非缺口。

## 4. 富档 batteries:Java ↔ Python 对称(一一对应)

| 类别 | Java | Python |
|---|---|---|
| atomic 执行器 | Http/Shell/Sql/StoredProc AtomicHandler | `_http/_shell/_sql/_stored_proc.py` |
| builtin handlers | DelimitedCodec/FileImport/HttpDispatch/QueryExport | `_delimited/_file_import/_http_dispatch/_query_export.py` |
| typed 模板 | Import/Export/Process/Dispatch | `_typed_import/export/process/dispatch.py` |
| 幂等件 | Idempotent/KeyResolver/Entity/Store/Handler | `_handler/_key_resolver/_entity/_store.py` |

## 4.5 Kafka offset-commit 契约

每条派单消息的处置决定一个 disposition,consumer 据此决定是否提交 offset。**权威源**:[`wire-protocol.md`](wire-protocol.md) §A、契约 fixture [`16/17/18-kafka-schema-version-*`](../api/sdk-contract-fixtures/) + [`28-kafka-paused-task-type-drop`](../api/sdk-contract-fixtures/)。命名:Java `DispatchDecision` / Go `MessageDisposition` / Python `DispatchDisposition` / Rust `MessageOutcome` / TS `PipelineOutcome`。

**契约钉死的 4 行 —— 五语言已一致**(2026-06-17 亲核 + Rust/TS 有断言测试):

| 场景 | 提交 offset? | 理由 / 权威 |
|---|---|---|
| 成功受理 / 重复投递(ACCEPTED) | ✅ 提交 | 已受理,offset 前移 |
| 缺失 / 空白 schemaVersion | ✅ 提交(按 v1) | fixture 16:缺省按 v1 **accept** |
| **未知大版本(v3+)** | **❌ 不提交** | **fixture 18 硬契约 `do NOT commit`** / `sdkMustNot: commit offset for a rejected message`。withhold → HOL 阻塞直到 SDK 升级(fail-loud) |
| fatal / draining / 平台 PAUSED\|DRAINING / 跨租户 | ❌ 不提交 | 瞬态 / 需重投到正确租户;seek 回本条 + pause 分区 |

> Rust/TS(薄档)同样实现以上 4 行:Rust `MessageOutcome::{RejectSchema,DropForeignTenant}` + `should_commit_offset()`(测试 `rejects_unknown_schema_version` 断言 `!should_commit_offset()`、`null_and_empty_schema_treated_as_v1`);TS `PipelineOutcome` 各分支带 `committed` 标志(`rejected-schema`/`dropped-tenant`/`backpressure` 均 `committed:false`)。

**decode / parse-error —— 已钉死为 commit-skip(fixture 30,五语言对齐)**

损坏 / 非 JSON / 无法反序列化的 poison 记录:**跳过并提交 offset(commit-skip)**,让分区前移过这条损坏数据——否则一条 corrupt 消息会永久 head-of-line 阻塞分区(重读→重败→永不前移)。§A 只约束 schemaVersion 不覆盖 decode,故此前 Java/Python(提交跳过)与 Go/TS(withhold)分歧;已加 [`30-kafka-decode-error-commit-skip`](../api/sdk-contract-fixtures/) fixture 钉死 + schema 加 `kafka:"commit-skip"` 枚举值,并把 Go/TS 对齐:

| SDK | decode/parse-error | 验证 |
|---|---|---|
| Java | DROP_TERMINAL → 提交 | `KafkaTaskConsumerCommitDecisionTest.terminalBadMessageCommitsOffset` |
| Python | DROP_TERMINAL → 提交 | contract runner `_assert_kafka_decode_commit_skip`(`_handle_record` 返 DROP_TERMINAL) |
| Go | DispositionDecodeError → 提交(已改) | `TestWorker_DecodeErrorCommitSkips` |
| TS | parse-error → `committed:true`(已改) | `consumer.test.ts` parse-error commit-skip |
| Rust | 解码是租户 adapter 的事(薄档引擎只收已解析记录) | conformance compute:非对象 body → `kafka:commit-skip` |

> `drop-message`(fixture 28 paused / schema-reject / foreign-tenant)= 跳过**不提交**(可重投);`commit-skip`(fixture 30 decode)= 跳过**并提交**(不可恢复的 poison)。两者是 schema `kafka` 枚举里语义相反的两个值。

**历史教训(2026-06-17)**:Java 曾对未知大版本返回 `DROP_TERMINAL`(提交,**违反 fixture 18** 静默丢 v3 任务);Python 早期无差别 commit 整批;decode 行 Go/TS 与 Java/Python 分歧。已在 PR #545/#546(disposition+schema)/ #550(诚实记录)/ 本 PR(decode 钉死)闭环。**核查此类问题必须逐 SDK 实际读 consumer 的 disposition 分支 + 比对 fixture,不能只看 happy-path 或假设"一致"**——decode 分歧正是逐语言通读 Rust/TS 实现才暴露的,需对全部五语言逐一核对后再下"五语言一致"结论。

## 5. 真正"没对齐"的——是工程尾巴 / 有意边界,不是代码缺口

| 项 | 现状 | 性质 |
|---|---|---|
| 发布渠道 | PyPI / crates.io / npm 尚未 publish(待 1.0) | 发布动作,非代码对齐 |
| 结构化日志 + traceId | Java 有 SLF4J+MDC;Go/Rust/TS 走租户 BYO 日志 | 薄档**有意**边界;可补的是示例文档而非 SDK 代码 |
| OTel/metrics exporter | Java 暴露 getter;其它语言租户自接 | 同上,BYO |

## 6. 结论

**该对齐的维度(协议引擎 / ADR-037 / 幂等 / 富档 batteries / Kafka offset-commit 契约)五语言已对齐,无需改代码的缺口。** 差异项要么是两档定位的有意设计(薄档无 typed/atomic/builtin、可观测 BYO),要么是发布动作(尚未 publish)。

> offset-commit 契约**五行全部钉死、五语言对齐**(见 §4.5):4 行 schema/paused/tenant 由 fixture 16/17/18/28 + PR #545/#546 闭环;decode/parse-error 行由 fixture 30 钉成 commit-skip,Go/TS 已对齐。

