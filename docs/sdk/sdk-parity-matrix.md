# 五语言 SDK 对齐矩阵(亲核版)

> **用途**:回答"五语言 SDK 哪些对齐 / 哪些没对齐"。每格都附 `文件:行号` 证据,可复核。
> **配套**:[`byo-sdk-guide.md`](byo-sdk-guide.md)(设计与定位)、[`byo-conformance-contract.md`](byo-conformance-contract.md)(契约用例)。
> **核查日期**:2026-06-17(基于当时 `main`)。改动 SDK 协议/能力后请同步本表。

## 0. 怎么读这张表 + 核查方法论(重要)

本表是**逐文件亲核**的结果,不是 grep 汇总。原因:**几次自动化审查(含 4 个并行 agent)在这件事上系统性误报**,根因是——

- **能力实现按语言惯例分散**:薄档(Go/Rust/TS)的**幂等**和 retry 打包在 `resilience.*` 里,**不在** `handler.*`。只 grep `handler.*` 会得出"无幂等"的错误结论。
- **富档模板路径有层级**:Python 类型化模板在 `handler/typed/_typed_*.py`,不在 `handler/_import.py`。查错路径会得出"没接 ADR-037"。

**复核守则**:判定某能力"缺失"前,必须 `grep -rn '<符号>' sdk/<lang>/`(全目录,不限单文件),并打开实现文件确认,不能凭单点 grep 或 agent 摘要下结论。判定"已落 main"要 `git show origin/main:<file>` 读实际内容,不看 PR 状态(见 [[feedback_shared_worktree_collisions]])。

## 1. 两档定位(有意,不是缺口)

| 档 | 语言 | 定位 | 含 |
|---|---|---|---|
| 富档 first-party | **Java / Python** | batteries-included | 协议引擎 + ADR-037 + 幂等 + **atomic 执行器**(shell/sql/http/storedproc)+ **typed 模板**(import/export/process/dispatch)+ **builtin handlers** |
| 薄档 BYO | **Go / Rust / TS** | 只稳协议,逻辑租户自带 | 协议引擎 + ADR-037 + 幂等 + 单一 `TaskHandler` 接口;**无** typed 模板 / atomic / builtin(有意) |

## 2. 协议引擎 + 生命周期(五语言**必须**对齐)

| 维度 | Java | Python | Go | Rust | TS |
|---|---|---|---|---|---|
| register 字段齐 | ✅ | ✅ | ✅ `lifecycle.go:137` | ✅ | ✅ |
| Kafka `workerType`(非旧 taskType) | ✅ `dispatcher/TaskDispatchMessage.java:37` `@JsonAlias` 兜底 | ✅ `dispatcher.py:337` | ✅ `consumer.go` | ✅ `kafka.rs` | ✅ `consumer.ts` |
| protocolVersion 注册门禁 | ✅ "v2" | ✅ "v2" | ✅ `lifecycle.go:137`(末位 supported) | ✅ register "v2" | ✅ |
| renew 409 → 弃任务/停 handler | ✅ | ✅ | ✅ `scheduler.go:277` | ✅ scheduler | ✅ `scheduler.ts:192` |
| renew `cancelRequested` → 取消 | ✅ | ✅ | ✅ `scheduler.go:214` MarkCancelled | ✅ | ✅ `scheduler.ts:150` |
| deactivate 优雅告别 | ✅ | ✅ | ✅ `lifecycle.go:369` | ✅ `lifecycle.rs:196` | ✅ `transport.ts:61` |
| backpressure pause/resume | ✅ | ✅ `_kafka.py:263` | ✅ `consumer.go` | ✅ | ✅ `consumer.ts` |
| 401/403 fail-fast | ✅ | ✅ | ✅ `decide.go` | ✅ | ✅ `transport.ts:14` |

> **非缺口小项**:Python `dispatcher.py:337` 用 `msg.get("workerType")`,无 `taskType` 兜底。BE v2 已不发 `taskType`(`batch-common/.../TaskDispatchMessage.java` 注释),故非正确性缺口;Java 的 `@JsonAlias` 只是 belt-and-suspenders。

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

## 5. 真正"没对齐"的——是工程尾巴 / 有意边界,不是代码缺口

| 项 | 现状 | 性质 |
|---|---|---|
| 发布渠道 | PyPI / crates.io / npm 尚未 publish(待 1.0) | 发布动作,非代码对齐 |
| 结构化日志 + traceId | Java 有 SLF4J+MDC;Go/Rust/TS 走租户 BYO 日志 | 薄档**有意**边界;可补的是示例文档而非 SDK 代码 |
| OTel/metrics exporter | Java 暴露 getter;其它语言租户自接 | 同上,BYO |

## 6. 结论

**该对齐的维度(协议引擎 / ADR-037 / 幂等 / 富档 batteries)五语言已对齐,无需改代码的缺口。** 差异项要么是两档定位的有意设计(薄档无 typed/atomic/builtin、可观测 BYO),要么是发布动作(尚未 publish)。
</content>
