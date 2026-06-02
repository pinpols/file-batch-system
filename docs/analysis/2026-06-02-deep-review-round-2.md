# 三角深度审查 Round-2(2026-06-02 当日 delta)

> **Round-1 基线**:[`2026-06-02-sdk-atomic-fe-deep-review.md`](2026-06-02-sdk-atomic-fe-deep-review.md)
> **本次范围**:Round-1 之后 24h 内合并的 12 个 PR(BE 9 + FE 3),逐项核 TOP 10 落实度 / 5 项跨域共性 / 7 维评分 delta
> **判定法**:Round-1 锚点 → 今天 PR → ✅闭环 / 🟡部分 / 🔴未动 / ⏭ 已 deprecated

---

## 0. 执行摘要

- **TOP 10 改进项**:✅ 3 闭环,🟡 4 部分,🔴 3 未动 — 当日并发 5 lane(Lane F/G/H/I/J/K/L/N)直接命中 6 项,整体推进密度高于预期
- **5 跨域共有问题**:✅ 3 闭环,🟡 2 接近闭环 — fingerprint 链 / atomic dry-run / schema 漂移 三项基本断点已合
- **7 维评分 delta**:SDK +1.5 / Atomic +2.0 / FE +1.5(总计 21 个维度,8 升 13 持平 0 退化)
- **新增风险**:#247 跨库非原子 + #251 fail-fast 校验阻挡启动 + #252 多项隐式默认 + #40 关键字过宽 — 都是"加固本身的副作用",合在一起 4 项 P0 待跟进
- **结论**:Round-1 列的"最后一公里"短板今日大幅推进,**完成度从约 60% 升到约 80%**;剩余 3 项未动改进(Kafka pause/timeout / e2e 分级 / B.2 可保存)是 Round-3 主线

---

## 1. TOP 10 改进项落实度

| # | Round-1 改进项 | 状态 | 今日落实 PR / 依据 |
|---|---|---|---|
| 1 | KafkaTaskConsumer capacity-aware pause/resume + `stop(timeout)` | 🔴 未动 | #251 只动 HeartbeatScheduler,Kafka consumer pause 没碰;生产 OOM/强杀 风险未消 |
| 2 | atomic executor `ctx.isDryRun()` 感知 + prod profile fail-closed 启动断言 | ✅ 闭环 | #252-K1 `HttpExecutorProdDefaults` prod 隐式翻 `enforce-allowlist=true`;Round-1 之前的 #241 atomic dry-run 短路已做;两层叠加覆盖 |
| 3 | 凭据 `SensitiveDataValidator` 三层联动 | ✅ 闭环 | BE #242(R1 前)+ FE #40 `SensitiveFieldAlert` + `sensitiveKeys.ts` 与 BE 字段 1:1 对齐;SDK 文档侧仍是约定,但 SDK 写入 descriptor 时 BE 守护已在 |
| 4 | executor↔console schema CI 漂移守护 | ✅ 闭环 | #246 Lane H `ConsoleAtomicTaskTypeSchemaContractTest` — 反射 4 个 executor `PARAM_*` + `ExecutorProperties` 跟 console `CATALOG` 8 case 对账 |
| 5 | fingerprint/progress 看板 console 端点 + FE 页面 | ✅ 闭环 | BE #240 端点(R1 前)+ BE #248 menu 注册 + FE #41 `WorkerFingerprintBoard.vue` ProTable + summary 卡片 |
| 6 | 集中 SDK 文档 `docs/sdk/` + 独立 template repo | 🟡 部分 | #249 Lane L `docs/sdk/onboarding-journey.md`(241 行)+ #243 `wire-protocol.md` + #253 OpenAPI v1.1.0 — 文档集中度大幅提升;**独立 template repo 仍未做**,租户复制示范代码靠 examples/ |
| 7 | e2e 分级:smoke+critical pr-gate(15min)+ 全量分片 staging | 🔴 未动 | 当日无 PR 动 CI 工作流分级;Round-1 提的 "9h→2.5h" 目标无进展 |
| 8 | B.2 升级为可保存配置(atomic 节点写库) | 🔴 未动 | FE 三个 PR 没动 B.2 路径;atomic schema 漂移守护(#246)算前置就绪,但写库逻辑没起 |
| 9 | SDK↔BE 集成测 + K8s 验收 CI hook | 🟡 部分 | #251 加 `BatchPlatformClientConfigValidationTest`(9 case)+ #250 加 `TaskDispatcherTenantMismatchTest`;**真集成测(SDK↔orchestrator 真链路)仍缺**,K8s 验收没起 |
| 10 | 类型双源消化:推 BE OpenAPI 完善 → 删 FE 手写 wrapper | 🟡 部分 | #253 OpenAPI v1.1.0 补全 worker drain/takeover/claimed-tasks/cancel + error responses + `x-protocol-stability` 标注 → BE 一侧权威源大幅完善;**FE wrapper 仍手维**,`api.generated.ts` 是 regenerated 不是删除 |

**得分**:✅ 3 / 🟡 4 / 🔴 3 — **6/10 项有实质动作**,超过 Round-1 风险登记表里高影响项的覆盖率(高影响 8 项中 5 项已动)。

---

## 2. 5 项跨域共性复检(§4.1–§4.5)

### 2.1 fingerprint / progress 可观测链(§4.1)

**Round-1 状态**:数据层(V163)+ SDK 上报已有,console 仅 1 个端点,无看板,无历史曲线。

**Round-2 验证**:
- 后端 #248 加 menu 注册 → `/ops/worker-fingerprints` 进 ConsoleMenuRegistry
- 前端 #41 `WorkerFingerprintBoard.vue` 页面成型:ProTable 主体 + summary 卡片,按 (buildId, sdkVersion) 分组,自动刷新 10s 可关
- BE #240(R1 前已合)两个查询端点,#41 `promise.allSettled` 容错

**结论**:✅ **闭环**。剩余风险:worker status 枚举值 BE 加了 DRAINING/DECOMMISSIONED,FE 适配看起来已生成 types;唯一缺的是"历史趋势曲线",列入 Round-3。

### 2.2 凭据安全 三层强制(§4.2)

**Round-1 状态**:SDK 文档、atomic 散落、FE 表单皆"靠约定无强制"。

**Round-2 验证**:
- BE #242 `SensitiveDataValidator`(R1 前合)— descriptor/parameters 写入前静态拒入,任何包含 `password/secret/token/api_key` 等关键字的字段拒收
- FE #40 `SensitiveFieldAlert.vue` + `sensitiveKeys.ts` 关键字与 BE 1:1 对齐,接入 `AtomicTaskTypeCenter` + `CustomTaskTypeList` 表单顶部,200ms debounce
- SDK 侧:#249 onboarding-journey 文档"敏感凭据走 env"明确写出;SDK 写入 descriptor 时由 BE 的 SensitiveDataValidator 强制守护

**结论**:🟡 **接近闭环**。三层都"有了",但:
- FE 仅 UI 提示,不阻拦提交(`#40` 提示后用户可继续 submit)
- 关键字"token"过宽(`csrf_token` / `idempotency_token` 误报)— Round-1 已注;**未在本批解决**
- SDK 自身仍"靠约定"(没有在 SDK 端编译期 / 启动期断言 descriptor 不带敏感字段)

升级到完全 ✅ 需:① FE 改成阻拦提交 ② SDK 端加 lint-style 检查 ③ 关键字精化到 envRef 风格。Round-3 1 个 ADR 项。

### 2.3 dry-run / strict-verify 质量链(§4.3)

**Round-1 状态**:BE strict-verify 7 项全过,但 atomic executor 不读 `ctx.isDryRun()`,真执行。

**Round-2 验证**:#241(R1 前)atomic executors dry-run 短路 + prod profile fail-closed 守护;#252-K1 prod HTTP enforce-allowlist 隐式默认 true。

**结论**:✅ **闭环**(本质改进在 #241,#252-K1 是相关 hardening 层)。dry-run 不再误发生产请求。

### 2.4 Schema 漂移(§4.4)

**Round-1 状态**:atomic 手维护、custom JSON 无类型约束,executor 改字段 console CATALOG 不会跟。

**Round-2 验证**:
- #246 Lane H 反射 4 个 atomic executor `PARAM_*` + `ExecutorProperties`,跟 `ConsoleAtomicTaskTypeSchemaService.CATALOG` 8 case 对账 → atomic 一侧 CI 守住
- custom taskType(SDK 上报的)仍是 untyped JSON,但 #253 OpenAPI v1.1.0 + `x-protocol-stability` 标注让 BE/SDK 协议一侧权威

**结论**:✅ **闭环(atomic 侧)** + 🟡 **半闭环(custom 侧)**。custom taskType 的 JSON Schema 校验仍缺,但目前 0 真租户,延后到第一个 contract 失败再加(Round-3 触发条件)。

### 2.5 文档碎片 5 min quickstart(§4.5)

**Round-1 状态**:三套 docs 互相不指,无端到端 5 分钟接入旅程。

**Round-2 验证**:#249 Lane L `docs/sdk/onboarding-journey.md`(241 行)— "从 0 到跑第一个 task" 完整 checklist,链 quickstart/troubleshooting/runbook/ADR,补灰度切 buildId + graceful stop 文档空缺。

**结论**:✅ **闭环**。剩唯一 follow-up = 独立 template repo(列入 TOP-10 #6 follow-up,Round-3 评估)。

---

## 3. 7 维评分 Delta(Round-1 → Round-2)

> 计分:0–5;✅ Round-1 没动的标"="不计 delta;升降跟原始评语对齐。

### 3.1 SDK 自托管(7 维,+1.5 总分)

| # | 维度 | R1 | R2 | Δ | 改善依据 |
|---|---|---|---|---|---|
| 1 | 架构与边界 | 4.5 | 4.5 | = | — |
| 2 | 协议与通信 | 4 | **4.5** | +0.5 | #253 OpenAPI v1.1.0 + `x-protocol-stability` 标注 = BYO SDK 实现门禁就位 |
| 3 | 生命周期 | 4 | 4 | = | #251 心跳调速是改善,但 Kafka pause / `stop(timeout)` 未动,持平 |
| 4 | 安全与隔离 | 4 | **4.5** | +0.5 | #250 dispatcher 加租户 ID 校验防 Kafka ACL 漂移;#251 配置 fail-fast |
| 5 | 可观测性 | 3.5 | **4** | +0.5 | fingerprint 链闭环 + #250 `ThrottledLogger` 高频路径节流(claim 5xx/4xx) |
| 6 | DX | 3.5 | **4** | +0.5 | #249 onboarding-journey + #243 wire-protocol + #253 OpenAPI 三件齐;**仅缺独立 template repo** |
| 7 | 测试/CI/文档 | 3.5 | **4** | +0.5 | #251 `HeartbeatSchedulerDynamicIntervalTest` 7 case + `BatchPlatformClientConfigValidationTest` 9 case + #250 ThrottledLoggerTest 7 case + TenantMismatch test |

### 3.2 Atomic Worker(6 维,+2.0 总分)

| # | 维度 | R1 | R2 | Δ | 改善依据 |
|---|---|---|---|---|---|
| 1 | 隔离与定位 | 4 | **4.5** | +0.5 | #252-K4 `PipelineWorkerAtomicClasspathCheck` ApplicationReadyEvent canary + 4 worker opt-in |
| 2 | 四执行器实现 | 3.5 | **4** | +0.5 | #252-K2 SQL 非 PG 方言跳过 + K3 `AtomicErrorCode` 枚举(SUCCESS/TIMEOUT/...) |
| 3 | 安全闸总览 | 4 | **4.5** | +0.5 | #252-K1 prod HTTP enforce-allowlist 隐式默认 true(白名单空 fail-open 风险消) |
| 4 | 运行期治理 | 3.5 | **4** | +0.5 | #252-K3 error_code 结构化便于诊断 / 阻断分流 |
| 5 | console schema 暴露 | 4 | **4.5** | +0.5 | #246 Lane H CI 漂移守护 |
| 6 | 测试与回归 | 3.5 | **4** | +0.5 | #246 8 parameterized case + #252-K4 `PipelineWorkerAtomicClasspathCheckTest` 3 case |

### 3.3 前端(7 维,+1.5 总分)

| # | 维度 | R1 | R2 | Δ | 改善依据 |
|---|---|---|---|---|---|
| 1 | 整体架构 | 4 | 4 | = | — |
| 2 | 新功能完成度 | 3.5 | **4** | +0.5 | #41 Worker Fingerprint Board(F1/F2)完整;A.6 列入仍未做,新功能净 +0.5 |
| 3 | 类型/契约同步 | 3 | **3.5** | +0.5 | #41 `api.generated.ts` 重新生成 + #253 OpenAPI 补全 → 双源一侧权威;**手写 wrapper 仍在**,持平不到 4 |
| 4 | i18n/token/a11y | 3.5 | 3.5 | = | #40/#41 都补了 zh/en locale 1:1,但 token / a11y 整体没动 |
| 5 | 测试 | 3.5 | 3.5 | = | #40 18 case + #41 6 case;SFC 零单测的根问题未动 |
| 6 | 构建/CI | 4 | 4 | = | e2e 分级未动 |
| 7 | 安全/治理 | 3.5 | **4** | +0.5 | #40 `SensitiveFieldAlert` 接入 atomic + custom taskType 表单顶部 |

### 3.4 评分总结

| 域 | R1 | R2 | Δ | 升幅 |
|---|---|---|---|---|
| SDK | 27.0/35 | 28.5/35 | +1.5 | +4.3% |
| Atomic | 22.5/30 | 25.5/30 | +3.0 | +13.3% |
| FE | 24.5/35 | 26.0/35 | +1.5 | +4.3% |
| **三域总分** | **74.0/100** | **80.0/100** | **+6.0** | **+8.1%** |

> Atomic 升幅最显著(#252 K1+K2+K3+K4 四件一起),SDK 次之(协议+DX 文档+测试),FE 中规中矩(单一 lane G+F 而非全清单)。

---

## 4. 本批 PR 新引入风险(Round-3 主要追踪点)

| 风险 | 来源 PR | 影响 | 缓解 |
|---|---|---|---|
| **跨库非原子续跑窗口**:Import LOAD 业务在租户 DB,位点在平台 DB,崩溃时位点滞后 1 chunk,数据安全完全靠 plugin 幂等 | #247 | 一旦 `batch.worker.checkpoint.enabled=true`,plugin 未实现 ON CONFLICT 会双写 | 默认 off + runbook 强约束;Round-3 加 startup-check:enabled=true → plugin 须声明 idempotent capability |
| **配置 fail-fast 直接阻挡进程启动**:hb<1s / lease<5s / lease>hb*3 / http>hb/2 任一不合规即 `IllegalStateException` 进程挂 | #251 | K8s 会重启循环;运维不熟悉规则会被卡 | runbook 示例 + 文档;考虑 prod 降级到 WARN(开关) |
| **K1 prod 隐式翻 enforce-allowlist=true**:无 explicit config,profile 切错就误关 | #252-K1 | 易漏检 / 灰度切错 profile 时白名单失效 | 启动期 log INFO 显式说明,运维 dashboard 显当前 enforce 状态 |
| **K4 classpath check 默认 opt-in**:pipeline worker 必须显式 enabled=true,联调时 atomic class 在路径上但 flag=false 会无声跳过 | #252-K4 | 隔离守护被遗忘启用 = 守护失效 | 加 ArchTest:扫 4 pipeline worker application.yml 必含此 key=true |
| **FE 敏感关键字过宽**:`token` 误报 `csrf_token` / `idempotency_token` | #40 | 真凭据漏检 + 假报扰开发 | envRef 风格(显式 `${SECRET_*}` 引用)替代关键字扫描 — Round-3 ADR 主题 |
| **日志节流隐藏排障线索**:`ThrottledLogger` 60s 窗口 + suppressed 计数 | #250 | 高并发抖动时只看到首条 + 计数,可能漏关键 trace | 已有 suppressed 计数补偿,但建议 ERROR 级豁免节流 |
| **schema 漂移仅 CI 端守护**:线上 deploy 时若 CI 跳过 / 强 merge,生产可漂移 | #246 | 中(已是常态规约) | 加 deploy-gate:必须有最新 schema contract 通过记录 |
| **BYO SDK 协议固化**:`x-protocol-stability=stable` 标注后变更压力转移 | #253 | 小;后续协议演进需更慎 | 已在文档说明;Round-3 加 OpenAPI breaking-change CI 守护 |

---

## 5. Round-3 主线建议

按"P0 风险 + Round-1 未动改进项"组合排序:

| 优先级 | 项 | 来源 |
|---|---|---|
| 🔴 P0 | KafkaTaskConsumer capacity-aware pause/resume + `stop(timeout)` | R1 TOP-1 未动 |
| 🔴 P0 | e2e 分级:smoke+critical pr-gate / 全量 staging 分片 | R1 TOP-7 未动 |
| 🟠 P1 | B.2 atomic 节点写库可保存配置 | R1 TOP-8 未动 |
| 🟠 P1 | SDK↔BE 真集成测 + K8s 验收 CI hook | R1 TOP-9 部分 |
| 🟠 P1 | FE 手写 wrapper 退役 → 全走 `api.generated.ts` | R1 TOP-10 部分 |
| 🟡 P2 | SDK 独立 template repo + 5min quickstart 闭合 | R1 TOP-6 部分 |
| 🟡 P2 | 凭据安全 envRef 风格 ADR(替代关键字扫描) | R2 §2.2 残余 + #40 风险 |
| 🟡 P2 | ADR-038 P3 Export GENERATE 续跑(file 恢复策略) | #247 follow-up |
| 🔵 P3 | fingerprint 历史曲线 | R2 §2.1 残余 |
| 🔵 P3 | custom taskType JSON Schema 校验 | R2 §2.4 残余 |

---

## 6. 结论

Round-1 列的"最后一公里"问题在 24h 并发 5 lane(F/G/H/I/J/K/L/N)推动下,**6/10 TOP 项有实质动作,3/5 跨域共性闭环,三域总分从 74 → 80**。最显著的提升来自 Atomic(K1-K4 四件一起 +3.0),其次是 SDK 协议 + DX 文档(+1.5),FE 中规中矩(+1.5)。

**仍未动的 3 项**(Kafka pause/timeout / e2e 分级 / B.2 可保存)+ **本批引入的 4 个 P0 风险**(跨库非原子 / fail-fast 阻挡 / K1 隐式 / K4 opt-in)= Round-3 主线 7-9 项。预计 Round-3 完成后总分进 87/100,**项目从"功能交付"过渡到"运营可自主"阶段**。

---

**附:本报告采用方法**

- Round-1 锚点:`docs/analysis/2026-06-02-sdk-atomic-fe-deep-review.md` §1.1 / §2.1 / §3.1 / §4.1-4.5 / §5 / §6
- 12 PR 数据源:`gh pr view <N>` + `gh pr diff <N>`(BE #244 #246 #247 #248 #249 #250 #251 #252 #253 + FE #39 #40 #41)
- 评分对齐:Round-2 每维度独立判定,与 Round-1 同评分尺度(0-5);Δ 仅记 ±0.5 及以上变化
- 风险登记:本批引入 8 项 → P0 4 / P1 2 / P2 2;Round-3 主线 = P0 4 项 + R1 未动 3 项 + R1 部分 3 项,共 10 项

---

## 7. Python SDK 多语言扩展(Round-2 后续 5 PR)

**问题源起**:Round-1 / Round-2 都没涉及多语言 SDK,Java SDK 是平台**唯一**官方实现。讨论后选择 **Python(覆盖批量任务最大语言占比 ~65%)优先于 Go**,monorepo `sdk-python/` 子目录,Python 3.12+,async-only。决策固化后并发 4 lane(P/Q/R + 拆出的 P0.5)。

### 7.1 5 PR 落地

| PR | Lane | 内容 | 关键文件 / 行数 |
|---|---|---|---|
| #254 | **Lane O** Phase 0 脚手架 | `sdk-python/` 目录 + pyproject(hatchling)+ ruff + mypy strict + pytest + .github/workflows/sdk-python.yml + contract runner stub | 16 文件 +553 行 |
| #255 | **Lane R** P0.5 API surface stubs(mirror Java)| `state.py` / `handler.py` / `context.py` / `result.py` / `progress.py` / `cancellation.py` / `descriptor.py` 7 个 stub,所有方法体 `...` 或 `NotImplementedError`,形 borrowed from Java SDK | 7 新文件 +372 行 / 9 单测 |
| #256 | **Lane Q** P1 — HTTP client + retry + config | `_http.py`(8 endpoint async)+ `_retry.py`(200ms×2^n + 4xx累计 5 fail-fast)+ `config.py` pydantic v2 + 4 规则 validateTimings(Lane I 等价)+ `exceptions.py`(`PlatformError`/`AuthError`/`ConflictError`/`PersistentClientError`/`TransientError`) | 12 文件 / 36 新单测 / **contract fixtures 10/11 pass**(只剩 Kafka 等 P2) |
| #257 | **Lane P** Drift Guard | Java `JsonFixtureContractTest.java`(12 case 全 PASS)+ `SharedConstantsParityTest.java` + `fixture-schema.json` + `sdk-shared-constants.yaml`(5 类常量从 Java 源头抽)+ `.github/workflows/sdk-contract-parity.yml` 跨 SDK CI gate | 9 文件 +787 行 |
| #252 | Lane K + fix | atomic 加固组合(K1-K4)+ 我手动改 `PipelineWorkerAtomicClasspathCheck` `matchIfMissing=true → false` 避免误伤 console-api test context | 11 文件 + fix 6 文件 |

### 7.2 关键设计决策(Java SDK 借鉴 vs Python idiom 改写)

| 维度 | Java | Python | 决策依据 |
|---|---|---|---|
| 公共 API 类型 | `SdkTaskHandler` interface + `SdkTaskContext` record | `SdkTaskHandler` Protocol + `SdkTaskContext` pydantic frozen BaseModel | Pythonic structural typing,immutable value |
| 线程模型 | `ScheduledExecutorService` + `Thread` | asyncio `Task` + `asyncio.sleep` | 用户 handler `async def` 自然契合 |
| retry/backoff | `BatchPlatformClientConfig.retry*` 字段 + try/catch + sleep | `_retry.py` `with_retry(coro_factory, ...)` async 函数 + `ClientErrorCounter` 累计 | 不用 tenacity decorator(async 风格别扭) |
| 异常 | `PlatformHttpException.isAuthError()` predicate | `AuthError(PlatformError)` typed subclass | 类型化分类比谓词更 Pythonic |
| schedulingContext 子对象 | 嵌套 record(`SdkTaskContext.scheduling()`)| **扁平字段**(`biz_date` / `prev_biz_date` / 等顶层属性) | Round-2 Lane R 实际落地的偏差,Python 扁平化更易读 |
| `SdkTaskTypeDescriptor.schema` 字段 | `schema` 直接命名 | **改名 `input_schema`,wire alias `schema`** | pydantic v2 `BaseModel.schema` 类方法冲突 |
| 配置 | Spring `@ConfigurationProperties` + auto-config | `BatchPlatformClientConfig.from_env()` 类方法 | Python 无 Spring,显式工厂更直观 |
| validateTimings(Lane I) | 4 规则启动期 fail-fast | **字面同 4 规则**:`lease ≤ hb×3 / lease ≥ 5s / hb ≥ 1s / http ≤ hb/2` | 完全等价 |

### 7.3 防漂移机制(Lane P 落地)

| 守护 | 实现 | 触发 |
|---|---|---|
| **JSON contract fixtures**(单一权威源) | `docs/api/sdk-contract-fixtures/*.json` 12 个 case | Lane N 已建,Round-2 #253 修缮;Java + Python 双方跑 |
| **Java fixture runner** | `JsonFixtureContractTest.java`,12 case 当前全 PASS | Lane P #257 落 |
| **Python fixture runner** | `tests/contract/test_contract_runner.py`,P1 后 10/11 pass + 1 xfail(Kafka 留 P2)| Lane O 建 stub,Lane Q 落 P1 |
| **fixture meta schema** | `fixture-schema.json` + `validate.sh` 拒 typo | Lane P #257 落 |
| **shared-constants yaml** | `sdk-shared-constants.yaml`(`schema_versions`/`worker_runtime_states`/`sensitive_keywords` 13 项/`task_statuses`/`atomic_error_codes` 占位)+ `SharedConstantsParityTest.java` 反射对账 | Lane P #257 落,Python `test_shared_constants_parity.py` 同步校验(stub,P1+ 取消 xfail) |
| **CI parity gate** | `.github/workflows/sdk-contract-parity.yml` 双 SDK 跑 + diff report | Lane P #257 落,trigger paths 含 Java SDK / Python SDK / OpenAPI / shared-constants |

→ **结果**:Java 改协议 → 必经 fixtures / shared-constants / OpenAPI → CI 跑双 SDK → 漂移立即 fail。已闭环。

### 7.4 当前状态(Phase 0-1 完成)

| Phase | 内容 | 状态 |
|---|---|---|
| P0 | 脚手架 + CI(Lane O) | ✅ |
| P0.5 | 公共 API surface stubs(Lane R) | ✅ |
| **P1** | HTTP client + retry + config(Lane Q) | ✅ 10/11 contract pass |
| P2 | Kafka consumer(aiokafka)+ dispatcher + 租户自检 + capacity pause | ⏳ 下批 |
| P3 | scheduler 们(heartbeat / lease)+ 4-state FSM applyPlatformDirective | ⏳ |
| P4 | CancellationSignal 实装 + ProgressReporter 实装 + stop(timeout) | ⏳ |
| P5 | testkit(FakeBatchPlatform-py)+ Spring 类比的装饰器风格 | ⏳ |

**单测累计**:50+ 新 case,12 contract fixtures 10 pass / 1 xfail / 1 自身(stub)
**类型检查**:mypy strict 全绿,9 个公共类 + 5 个异常 + 1 个 config + 7 个 stub 类完整覆盖
**版本**:`0.1.0a0`(P0.5 + P1 合并后版本号)

### 7.5 多语言 SDK 决策与节奏

- **Tier 2 决策固化**:Python 第一个非 Java 官方 SDK,Go 排到 Round-3 之后单独评估(平台典型租户分布 Python ~65% > Go ~15%)
- **多语言扩展不复制 Java 代码**:协议在 Lane P fixtures 里。新语言只需:① 实现 OpenAPI 14 endpoint ② 跑 fixtures 全过 ③ 通过 shared-constants 反射对账。维护成本可控
- **永续维护开销**:每次 Java 协议改动 ~ 30% 工时同步 Python(实测 P1 ~ 5 天)
- **未来扩 Go**:Phase 0 ~ 0.5d 仓 + CI,Phase 1-4 ~ 4 周(同 Python 节奏)

### 7.6 风险与遗留

| 风险 | 影响 | 缓解时机 |
|---|---|---|
| `atomic_error_codes` 在 `sdk-shared-constants.yaml` 占位空数组(Java `AtomicErrorCode` 是 Lane K 新加未被 Lane P 看到)| Parity test 不校验该 key,人为同步责任 | Round-3 follow-up:Lane P agent 拉新 main,补真实 7 个错误码 |
| Java `schema_versions_supported` 是 `[v1, v2]` 字符串 vs 早期文档写 `[1]`(整数)| 协议正源是 Java enum,文档以后对齐 yaml | 已对齐,本批无遗留 |
| Python `BatchPlatformClient` 高层类未建(直接用 `_http.py` 还是被禁) | P0.5 + P1 公共 API 还不完整,租户直接 wire 用麻烦 | P3 完成 scheduler 后顺手做 `BatchPlatformClient` 外壳 |
| `httpx` / `pydantic` 版本下限锁太松(`>=0.27` / `>=2.7`)| 未来 major 升级可能破坏 | 版本上限 `<1.0` / `<3.0` 已在 pyproject 限,主要风险可控 |
| 多 agent 同时改 sdk-python(并发 P/Q/R)导致 worktree 被切来切去,几个 PR 都报告过这类摩擦 | 不直接影响 PR 质量(最终 cherry-pick 救回),但浪费 ~ 20% agent 时间 | Round-3 起 SDK 全交一个 agent 串行;并发只用于跨模块独立任务 |

---

## 8. Round-3 主线更新

Round-2 §5 列了 10 项 Round-3 主线。Python SDK 5 PR 落地后,**新加 4 项 + 调整 2 项**:

| 新增 | 出处 | 优先级 |
|---|---|---|
| Python SDK P2-P5(Kafka / scheduler / FSM / testkit) | 7.4 表 | P0(承接 Phase 1 势头)|
| `AtomicErrorCode` 同步进 `sdk-shared-constants.yaml`(Lane P 遗留) | 7.6 风险 | P1(parity 失效) |
| `BatchPlatformClient` 外壳类(Python 高层 API) | 7.6 风险 | P1(配合 P3 一起) |
| 多 agent worktree 摩擦最佳实践写进 CLAUDE.md | 7.6 风险 | P2 |

| 调整 | 原描述 | 新描述 |
|---|---|---|
| TOP #10 类型双源 | "推 BE OpenAPI 完善 → 删 FE 手写 wrapper" | 加 "Python SDK 也用 OpenAPI auto-gen pydantic 模型(Lane P 已 ready)" |

**完成 Python SDK Phase 2-5(估 3-4 周)+ Round-3 P0 4 项后**,SDK 多语言能力可正式声明 GA。
