# JDK 特性使用分析报告（JDK 8 → 25）

日期：2026-06-09

## 结论

当前项目主工程以 **JDK 25** 编译运行，但源码层面主要使用的是 **JDK 8 → 21** 的稳定语言/API 特性；尚未显式使用 JDK 22/23/24/25 的核心新增编程模型。

- 主工程：`pom.xml` 配置 `java.version=25`、`maven.compiler.release=25`。
- 压测模块：`load-tests/pom.xml` 单独配置 `maven.compiler.release=21`。
- 未启用虚拟线程：未发现 `Thread.ofVirtual`、`Executors.newVirtualThreadPerTaskExecutor`、`spring.threads.virtual.enabled=true`。
- JDK 25 当前更多是“编译/运行时基线”，不是“语言/API 新特性重度使用”。

## 扫描依据

本报告基于以下项目内扫描：

- Maven/JDK 配置：`pom.xml`、`load-tests/pom.xml`。
- Java 源码语法/API：`record`、sealed、switch expression、text block、`List.getFirst()`、`ScopedValue`、`Thread.ofVirtual`、`Gatherers` 等关键字/API。
- 虚拟线程配置：`spring.threads.virtual.enabled`、`newVirtualThread`、`Thread.ofVirtual`。
- JDK 25 特性清单参考：OpenJDK JDK 25 官方页面（https://openjdk.org/projects/jdk/25/）。

## 实际使用的 JDK 特性

| JDK 版本 | 项目实际使用情况 | 说明 |
| --- | --- | --- |
| JDK 8 | 大量使用 | Lambda、Stream API、Optional、`java.time`、CompletableFuture。 |
| JDK 9 | 大量使用 | `List.of`、`Map.of`、`Set.of`、`InputStream.transferTo`。 |
| JDK 10 | 有使用 | `var` 局部变量、`List.copyOf`、`Map.copyOf`、`Set.copyOf`。 |
| JDK 11 | 大量使用 | `String.isBlank`、`Files.readString/writeString`、`Path.of`、标准 `java.net.http.HttpClient`。 |
| JDK 12 | 少量使用 | `Collectors.toUnmodifiable*` 等 Collector。 |
| JDK 13/15 | 大量使用 | Text Blocks `"""..."""`，主要在 SQL、JSON、测试 fixture 中。 |
| JDK 14 | 有使用 | Switch expression / arrow switch：`case X ->`、`return switch (...)`。 |
| JDK 16 | 大量使用 | `record`、`instanceof` pattern matching。 |
| JDK 17 | 有使用 | sealed interface，例如 `PageQuery`。 |
| JDK 21 | 有使用 | `List.getFirst()` / `getLast()`，来自 Sequenced Collections。 |
| JDK 22 | 未发现明确使用 | 未发现 FFM API、未发现 22 独有语言/API 依赖。 |
| JDK 23 | 未发现明确使用 | 未发现 Markdown JavaDoc 风格或 23 独有代码特性。 |
| JDK 24 | 未发现明确使用 | 未发现 Stream Gatherers、Class-File API、虚拟线程 pinning 相关显式改造。 |
| JDK 25 | 未发现明确使用 | 未发现 `ScopedValue`、module import、Flexible Constructor Bodies、JDK 25 KDF API 等代码级使用。 |

## 可引入新特性评估矩阵（JDK 8 → 25）

本节不是“项目已经用了什么”，而是从当前批处理后端的架构、上线风险、性能瓶颈、安全边界和团队维护成本出发，评估 JDK 8 到 JDK 25 中还能继续引入或治理的特性。

评价口径：

- **已广泛使用**：项目已有大量依赖，属于既成事实。
- **建议继续推广**：收益明确、稳定、适合本项目。
- **可试点**：有收益但需要小范围验证。
- **谨慎/暂缓**：收益不明确、会增加复杂度，或涉及 preview/incubator。
- **不建议**：与 Spring Boot 多模块生产项目不匹配。

| JDK | 可引入/可治理特性 | 当前状态 | 项目落点 | 收益 | 风险 | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| 8 | Lambda、Stream、Optional、`java.time`、CompletableFuture | 已广泛使用 | mapper/service 组装、异步 outbox、时间模型 | 可读性、时间类型正确性、异步编排 | Stream 过度链式会降低可调试性；Optional 不应做字段类型 | 继续使用；复杂业务链路优先可读性，不做炫技式 Stream |
| 8 | `java.time` 全面替代 `Date` / `Calendar` | 已广泛使用 | 调度、业务日、SLA、审计时间 | 时区/DST 语义更稳 | `LocalDateTime` 与 `Instant` 混用易错 | 继续治理时间边界，DB/接口优先明确 UTC 与业务时区 |
| 8 | CompletableFuture | 已使用 | Kafka publish、异步 forwarder、best-effort 升级 | 异步化、失败隔离 | 异常吞噬、线程池不受控 | 仅在有明确 executor 和超时策略时使用 |
| 9 | `List.of` / `Map.of` / `Set.of` | 已广泛使用 | 测试 fixture、小型不可变配置 | 简洁、不可变 | 不接受 null，超大 map 可读性差 | 继续使用；大对象构造改 builder/fixture helper |
| 9 | `InputStream.transferTo` | 已使用 | 文件复制、对象存储、加解密流 | 减少手写 copy loop | 必须明确 size/limit，否则容易形成无界读取 | 可用，但所有对象存储写入必须配 exact/bounded stream |
| 9 | JPMS 模块系统 | 未使用 | 理论上可拆模块边界 | 强封装 | Spring Boot/Maven 多模块改造成本极高 | 不建议引入 |
| 10 | `var` 局部变量 | 已使用 | 测试、局部临时变量 | 降低噪音 | 复杂泛型/业务关键变量会降低可读性 | 保守使用；右侧类型不明显时不用 |
| 10 | `List.copyOf` / `Map.copyOf` / `Set.copyOf` | 已使用 | DTO、配置快照、registry | 防御性不可变拷贝 | 不接受 null | 建议继续推广到边界对象和 registry |
| 11 | `String.isBlank`、`strip` | 已广泛使用 | 参数校验、配置校验 | Unicode 空白语义更合理 | 与 `trim` 语义略不同 | 继续使用 |
| 11 | `Files.readString/writeString`、`Path.of` | 已使用 | 测试、fixture、脚本配置读取 | 简化文件读写 | 大文件不能直接 readString | 仅用于小文件/测试/配置，生产大文件继续流式 |
| 11 | 标准 `java.net.http.HttpClient` | 已使用 | SDK/orchestrator HTTP 调用、atomic HTTP task | 减少第三方依赖、支持异步 | 与连接池/超时/重试治理要统一 | 继续使用，但必须统一 timeout、retry、指标 |
| 12 | `Collectors.teeing`、unmodifiable collectors | 少量使用 | 统计聚合、校验结果聚合 | 单次遍历完成多指标 | 表达式可能难读 | 可用但不强推；复杂统计用显式对象更清楚 |
| 13/15 | Text Blocks | 已广泛使用 | SQL、JSON、测试 fixture、OpenAPI 测试 | 大幅提升 SQL/JSON 可读性 | 生产代码内联 SQL 可能违背 SQL 分离治理 | 测试/fixture 推荐；生产 SQL 继续优先 XML/资源文件/专用模板 |
| 14 | Switch expression / arrow switch | 已使用 | 枚举映射、状态机、类型分发 | 分支表达更紧凑，遗漏分支更明显 | 复杂分支中嵌套逻辑难读 | 建议继续用于 enum/status/type 映射 |
| 14 | Helpful NullPointerException | 运行时默认收益 | 排障 | NPE 定位更快 | 不能替代参数校验 | 保持开启默认行为 |
| 16 | `record` | 已广泛使用 | 查询对象、DTO、内部 value object、测试 stub | 不可变、样板少、值语义明确 | 不适合 JPA/MyBatis 可变 entity；JSON 兼容需测试 | 继续使用在 DTO/value object；禁止滥用在需要可变生命周期的实体 |
| 16 | Pattern matching for `instanceof` | 已广泛使用 | 参数解析、Map/JSON 解包、异常分发 | 减少强转，降低 bug | 复杂嵌套仍需拆方法 | 继续使用 |
| 17 | Sealed classes/interfaces | 已使用 | `PageQuery` offset/cursor 双轨模型 | 封闭类型集合，状态更可控 | 与 Jackson/MyBatis 反序列化需额外测试 | 可推广到状态机命令、结果类型、错误分类等封闭模型 |
| 17 | Pattern matching for switch | 仍需注意版本状态/用法 | 复杂类型分发 | 表达力强 | 历史上经历 preview，团队容易误用 | 当前不主动推广，必要时先确认 release 25 下稳定语法 |
| 17 | 强封装 JDK internals | 运行时约束 | 依赖治理 | 提前暴露非法反射 | Mockito/agent/老库兼容问题 | CI 保持 JDK 25 跑，依赖升级要看 illegal-access/agent 警告 |
| 18 | UTF-8 by Default | 运行时默认收益 | 配置、CSV、日志、测试 fixture | 跨环境编码更一致 | 老系统 GBK/本地编码文件会暴露问题 | 明确文件编码；导入场景仍必须尊重业务配置的 charset |
| 18 | Simple Web Server、Code Snippets in JavaDoc | 未使用 | 本地文档/示例 | 辅助开发 | 与生产无关 | 不纳入主工程治理 |
| 19 | 虚拟线程（preview）、结构化并发（preview） | 未使用 | 无 | 概念验证 | preview、参数复杂 | 不使用 JDK 19 preview 特性 |
| 20 | Scoped Values / Structured Concurrency preview | 未使用 | 无 | 早期探索 | preview | 不使用 JDK 20 preview 特性 |
| 21 | 虚拟线程正式版 | 未启用 | console-api HTTP/SSE、内部 HTTP client、低风险阻塞任务 | 高并发 I/O 下减少平台线程占用 | ThreadLocal/MDC/SecurityContext 传播、阻塞点 pinning、连接池仍是瓶颈 | P2 仅 console-api profile 试点，不全系统切换 |
| 21 | Sequenced Collections：`getFirst/getLast` | 已使用 | 列表首尾访问 | 表达意图清晰 | 空集合仍会抛异常 | 可用；空集合先显式判断 |
| 21 | Record patterns / pattern switch | 需谨慎 | DTO 解构、封闭结果处理 | 可读性提升 | 团队熟悉度、Jackson/框架边界无收益 | 不做机械替换，领域模型局部可试 |
| 21 | Generational ZGC | 可运行时评估 | 大内存压测、长时间 worker | 降低暂停 | GC 参数误配会掩盖真实瓶颈 | 仅在压测矩阵中比较 G1/ZGC，不默认切 |
| 22 | Foreign Function & Memory API 正式版 | 未使用 | 理论上可接 native 压缩/校验/加密库 | native 互操作 | 当前无 native 需求，安全和可移植性成本高 | 不引入 |
| 22 | Unnamed Variables & Patterns | 未使用 | 测试/回调忽略参数 | 降低噪音 | 团队风格不统一 | 可在测试中少量使用，生产不强推 |
| 22 | Stream Gatherers preview | 未使用 | chunk/window 流处理 | 可表达窗口聚合 | preview | 不用 JDK 22 preview，等 JDK 24 正式版 |
| 22 | Class-File API preview | 未使用 | 架构规则/字节码扫描 | 替代部分 ASM/ArchUnit 内部需求 | preview 且当前 ArchUnit 已满足 | 不引入 |
| 22 | Launch Multi-File Source-Code Programs | 未使用 | 运维小工具/一次性脚本 | 减少脚本工程化成本 | 不适合生产主链路 | 可用于本地 spike，不进主服务 |
| 23 | Markdown Documentation Comments | 未使用 | SDK、SPI、插件示例文档 | JavaDoc 更易写、示例更清楚 | 文档风格迁移成本 | 推荐用于 SDK/SPI 新文档，不要求全量迁移 |
| 23 | ZGC Generational Mode by Default | 运行时可评估 | 大堆/低暂停场景 | 降低 GC pause | 不是所有服务都收益 | 压测矩阵里评估，不默认替换 G1 |
| 23 | Module Import preview、Flexible Constructor Bodies preview | 未使用 | 无 | 语法便利 | preview | 不引入 |
| 24 | Stream Gatherers 正式版 | 未使用 | lease renew chunk、outbox 批处理、文件分片窗口、分页流水处理 | 可读性和复用性提升 | 团队熟悉度低，过度函数式会难排障 | P2 选择 1-2 个 chunk/window helper 试点 |
| 24 | Class-File API 正式版 | 未使用 | security-scan、自研 arch rule、字节码元数据分析 | 降低对内部 classfile 解析依赖 | 当前 ArchUnit 足够 | P3 工具模块可评估，业务模块不引入 |
| 24 | Synchronize Virtual Threads without Pinning | 运行时收益 | console-api 虚拟线程试点 | 降低 synchronized pinning 风险 | 不代表所有阻塞都安全 | 支撑 VT 试点，但不是单独改代码的理由 |
| 24 | AOT Class Loading & Linking | 未使用 | 启动优化 | 缩短启动 | Spring Boot 多模块收益需实测 | P3，除非启动时间成为明确瓶颈 |
| 24 | Quantum-resistant crypto APIs | 未使用 | 密钥交换/签名场景 | 抗量子算法支持 | 当前业务无协议层需求 | 不引入 |
| 25 | Scoped Values 正式/稳定方向 | 未使用 | requestId、traceId、tenantId、batch context | 替代部分 ThreadLocal，适合虚拟线程 | 与 MDC/Spring Security/异步传播兼容要验证 | P2 小范围实验，不替换主鉴权上下文 |
| 25 | KDF API | 未使用 | API key、token 派生、内部 secret 派生 | 标准化 KDF 封装 | 当前 PBKDF2 已可用，迁移需兼容老数据 | P3；安全收益不如密钥轮换/pepper/审计 |
| 25 | Flexible Constructor Bodies | 未使用 | value object 构造参数预校验 | 构造器更自然 | 改造收益小 | 不主动重构 |
| 25 | Compact Object Headers | 未使用 | 高对象数服务内存优化 | 降低 heap 占用 | 实验/参数依赖，需压测 | 仅纳入 JVM tuning 实验 |
| 25 | JFR Method Timing & Tracing | 未使用 | 高压瓶颈定位、上线前性能分析 | 低侵入定位慢方法 | 采集配置和数据量控制 | 推荐纳入压测 runbook |
| 25 | Module Import / Compact Source Files | 未使用 | demo/脚本 | 简化示例 | 不适合 Spring Boot 主工程 | 不引入主工程 |
| 25 | Structured Concurrency、Stable Values、Primitive Patterns、Vector API | 未使用 | 理论上可用于并发编排/缓存/数值处理 | 潜在收益 | preview/incubator，生产复杂度高 | 暂缓，禁止进入生产 profile |

## 推荐引入路线

### P1：不改业务语义、上线前可做

1. **JDK 25 toolchain 固化**
   - CI、Docker、Maven、README、runbook 明确 JDK 25。
   - 防止本地 JDK 21/17 编译导致隐藏差异。

2. **JFR/JVM 观测补齐**
   - 把 JDK 25 JFR method timing、CPU profiling、GC、heap histogram 纳入压测脚本和 runbook。
   - 优先服务：orchestrator、console-api、worker-import、worker-export、worker-process。

3. **继续治理不可变边界**
   - DTO/value object 继续用 `record`。
   - registry/config snapshot 使用 `copyOf`。
   - 状态封闭模型可用 sealed，但只在类型集合稳定时引入。

### P2：上线后小范围试点

1. **console-api 虚拟线程 profile**
   - 新增独立 profile，不影响 worker/orchestrator。
   - 覆盖 SSE、dashboard、列表查询、登录鉴权、文件接口。
   - 观察 DB pool 等待、MDC/traceId、线程数、P99。

2. **JDK 24 Stream Gatherers**
   - 只挑一个重复度高的 chunk/window 场景试点。
   - 备选：lease renew 分块、outbox 批处理、文件分片窗口。
   - 成功标准是可读性提升，不以减少行数为目标。

3. **Markdown JavaDoc 用于 SDK/SPI**
   - `batch-worker-sdk`、`BatchTaskExecutor`、插件示例优先。
   - 不做老文档全量迁移。

### P3：性能或平台专项再做

1. **Scoped Values**
   - 只在非鉴权主链路试验 request context。
   - 先与 MDC、Spring Security、异步执行器传播做兼容验证。

2. **KDF API**
   - 当前 PBKDF2 方案可继续。
   - 若后续做 key derivation 抽象层，再评估 JDK 25 KDF API。

3. **Class-File API**
   - 仅 security-scan/arch tooling 可能有价值。
   - 当前 ArchUnit 足够，不迁移。

4. **ZGC/Shenandoah/Compact Object Headers**
   - 作为 JVM 参数矩阵测，不作为代码重构项。

## 关键扫描量级

以下是本次扫描得到的近似量级，用于判断“是否偶发使用”还是“项目级依赖”：

| 特性/API | 近似使用量级 |
| --- | ---: |
| Lambda / `->` | 约 1997 处 |
| `record` | 约 702 处 |
| `List.of` / `Map.of` / `Set.of` | 约 3000+ 处 |
| `String.isBlank` | 约 477 处 |
| Text Blocks | 约 595 处 |
| `var` 局部变量 | 约 218 处 |
| Switch expression / arrow switch | 约 140 处 |
| `List.getFirst()` / `getLast()` | 约 10 处 |

## 虚拟线程现状

当前项目没有实际启用虚拟线程。

未发现：

- `Thread.ofVirtual(...)`
- `Executors.newVirtualThreadPerTaskExecutor()`
- `spring.threads.virtual.enabled=true`

文档中已有历史判断：

- `docs/archive/analysis/file-io-evaluation-2026-05-03.md`：当时结论为不主动切虚拟线程。
- `docs/runbook/jvm-tuning-and-profiling.md`：记录了 console-api 可评估开启 `spring.threads.virtual.enabled: true`。

### 是否应该启用

建议不要全系统一刀切启用。更合理的是 **console-api 先做灰度试点**：

- console-api 是 HTTP/SSE I/O 型服务，更符合虚拟线程收益场景。
- orchestrator、worker 有大量显式线程池、Kafka listener、任务池、背压、lease renew、数据库事务和文件处理，直接切换风险更高。
- JDK 24+ 已改善虚拟线程在 `synchronized` 上的 pinning 问题，但这仍不等于所有阻塞点都天然安全。

建议试点方式：

1. 仅在 console-api 增加独立 profile，例如 `virtual-thread`.
2. 配置 `spring.threads.virtual.enabled=true`。
3. 压测接口：SSE、文件查询、dashboard、列表查询、登录鉴权、OpenAPI 高频请求。
4. 观测指标：线程数、CPU、GC、P99、DB pool 等待、MDC/traceId 丢失、Spring Security 上下文传播。
5. 验证稳定后再考虑 orchestrator 内部 HTTP 客户端或低风险异步任务点。

## JDK 25 新特性适配评估

OpenJDK JDK 25 官方列出的主要 JEP 包括：

- JEP 506：Scoped Values
- JEP 510：Key Derivation Function API
- JEP 511：Module Import Declarations
- JEP 512：Compact Source Files and Instance Main Methods
- JEP 513：Flexible Constructor Bodies
- JEP 519：Compact Object Headers
- JEP 520：JFR Method Timing & Tracing
- JEP 521：Generational Shenandoah
- 以及若干 preview/incubator/experimental 特性

### 可考虑使用

| 特性 | 项目适配价值 | 建议 |
| --- | --- | --- |
| Scoped Values | 可替代部分 ThreadLocal/MDC 上下文传递。 | P2 试点，不建议上线前大范围替换。 |
| KDF API | 可替换当前 PBKDF2 `SecretKeyFactory` 封装。 | 收益有限，安全上不是 P0。 |
| Compact Object Headers | 可降低对象头内存占用。 | 更偏 JVM 参数/运行时收益，需压测验证。 |
| JFR Method Timing & Tracing | 适合定位高压下慢方法、CPU 消耗。 | 推荐纳入性能压测 runbook。 |
| Flexible Constructor Bodies | 可在 `super(...)` 前做参数校验。 | 业务收益小，不建议主动重构。 |

### 不建议现在使用

| 特性 | 原因 |
| --- | --- |
| Structured Concurrency | JDK 25 仍是 preview，需要 `--enable-preview`，生产门禁复杂度上升。 |
| Stable Values | preview，不建议生产主链路依赖。 |
| Primitive Types in Patterns | preview，不建议引入。 |
| Vector API | incubator，当前批处理热点不明确，不建议为了新特性重写。 |
| Compact Source Files / Instance Main Methods | 更适合脚本/教学，不适合 Spring Boot 多模块生产项目。 |
| Module Import Declarations | 对当前 Maven/Spring 多模块工程收益很小。 |

## 对当前项目的基线判断

当前代码已经无法合理回退到 JDK 8/11/17：

- JDK 16 `record` 使用量很大。
- JDK 17 sealed 已进入公共分页模型。
- JDK 21 `List.getFirst()` / `getLast()` 已在生产代码出现。
- Text Blocks、switch expression、pattern matching 已广泛使用。

如果要降低基线，改造成本较高且收益有限。当前建议：

- **构建/运行基线保持 JDK 25**。
- **最低代码语义基线可视为 JDK 21+**。
- 生产镜像、CI、IDE、Maven toolchain 必须统一到 JDK 25，避免“本地能编、CI 不一致”。

## 后续治理计划

### P1：上线前建议做

1. 固化 JDK 25 toolchain：
   - Maven toolchain 或 CI setup-java 明确 JDK 25。
   - Docker base image 统一 JDK 25。
   - README / runbook 写清“不支持 JDK 17/21 直接构建主工程”。

2. 保持不启用 preview：
   - CI 增加 grep，禁止 `--enable-preview` 进入生产 profile。
   - preview/incubator 仅允许在 spike 或 benchmark 模块实验。

3. 补充 JVM/JFR runbook：
   - JDK 25 下 JFR CPU profiling、method timing、GC 观测参数。
   - 高压测试统一采集线程数、GC、JFR、heap histogram。

### P2：上线后试点

1. console-api 虚拟线程灰度：
   - 新增 profile 或环境开关。
   - 只在 console-api 开，worker/orchestrator 不跟随。
   - 压测 SSE 和列表查询。

2. Scoped Values 小范围实验：
   - 先在非核心路径验证 requestId / traceId / tenantId 传播。
   - 不替换 Spring Security/MDC 主链路，避免隐性上下文丢失。

3. Stream Gatherers 代码清理：
   - 适合批量 chunk/window 处理逻辑。
   - 仅在可读性明显提升时使用，不做机械替换。

### P3：暂缓

1. JDK 25 KDF API 替换 PBKDF2 封装。
2. Class-File API 替换 ArchUnit/字节码扫描依赖。
3. Vector API / Stable Values / Structured Concurrency 等 preview/incubator 方向。
