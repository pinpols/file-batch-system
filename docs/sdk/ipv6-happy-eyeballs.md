# SDK IPv6 / Happy Eyeballs

本文约束五语言 SDK 到平台 `/internal/*` 控制面 HTTP 的双栈连接行为。Kafka broker 选择、租户 handler 访问的业务 URL 和平台内建 Worker 不属于同一个连接边界，不在此处共用实现。

## 当前矩阵

| SDK | 控制面 HTTP 实现 | 当前双栈行为 | 状态 |
|---|---|---|---|
| Go | `net/http` + 显式 `net.Dialer` | `FallbackDelay=250ms`，IPv6 首连接阻塞时并行尝试 IPv4 | 已显式固化并通过真实 socket 矩阵 |
| TypeScript | Node 22 `http` / `https` | SDK 专用 Agent 显式固定 `autoSelectFamily=true`、attempt timeout 250ms | 已显式固化并通过真实 socket 矩阵 |
| Python | `httpx` → `httpcore` → AnyIO | 当前受支持依赖集合由 `anyio.connect_tcp()` 提供 250ms Happy Eyeballs | 新鲜依赖解析和真实 socket 矩阵已通过 |
| Rust | `reqwest` → `hyper-util` | 仓库 `Cargo.lock` 当前解析版本的 connector 默认启用 300ms Happy Eyeballs | 当前解析版本和真实 socket 矩阵已通过 |
| Java core | OkHttp 5 | `fastFallback=true`,默认约 250ms 后竞速下一地址；禁用连接层自动重试和重定向 | 已在现有 core 内固化并通过真实 socket 矩阵 |

Python 和 Rust 的行为来自依赖实现，不提升为本 SDK 自定义 API。Python wheel 的范围依赖和 Rust crate 的 SemVer
依赖不会把租户永远锁在仓库当前解析版本；`uv.lock` / `Cargo.lock` 只保证仓库复现，不能代表下游锁文件。
Python CI 每次从 `pyproject.toml` 新鲜解析依赖，Rust 则在依赖升级 PR 更新 `Cargo.lock` 后复跑本测试。升级
`httpx/httpcore/anyio` 或 `reqwest/hyper-util` 时必须复核该矩阵，不能只看普通请求成功测试。

## Java 实施边界

Java core 保持 Spring-free 和单一制品，内部 `PlatformHttpClient` 直接使用仓库统一版本的 OkHttp 5。SDK 的公共类型、配置字段和调用方 wiring 不变；OkHttp 类型不暴露到公共 API，也不新增平行 SDK artifact。

JDK 21/25 的公开 `java.net.http.HttpClient` API 均没有 Happy Eyeballs 开关、竞速延迟或 DNS/Socket 注入点，因此不能把实现细节当作可验证的 SDK 契约。core 不自行接管 TLS/SNI、HTTP/2、代理和连接池，而由 OkHttp 提供连接竞速。

为保护非幂等控制面 POST，transport 明确设置 `retryOnConnectionFailure=false`、关闭 HTTP/HTTPS 自动重定向；业务重试仍只由 SDK 的 CLAIM/REPORT 决策层持有。代价是租户运行时增加 OkHttp、Okio 和 Kotlin 标准库依赖，但 core 自身 thin jar 仍受 `< 2 MB` 门禁约束。

## 不混入本阶段的路径

- Java/Python batteries 中 Atomic/HTTP Dispatch 的目标来自任务参数，属于不可信 URL。它们必须先完成 A/AAAA 全量校验、受限地址拒绝和 DNS 快照固定，再接入竞速连接。
- Kafka 的 broker failover 由各语言 Kafka 客户端负责。单个 broker hostname 的 A/AAAA 连接策略需单独验证，不能用 HTTP 结果替代。
- HTTP Happy Eyeballs 只降低单地址族黑洞造成的建连延迟，不替代业务重试、熔断或幂等。

## Staging 全矩阵门槛

| 场景 | 验收要求 |
|---|---|
| IPv4-only / IPv6-only | 两种单栈均能完成 register 或最小 HTTP 往返 |
| IPv6 黑洞、IPv4 正常 | 在首连接超时前触发 fallback，记录切换耗时 |
| IPv4 黑洞、IPv6 正常 | 同上，反向验证 |
| 双栈均可达 | 只产生一个业务请求，不因竞速重复 POST |
| 全部地址不可达 | 在请求总超时内失败，不留下后台 socket |
| 连接成功后的 5xx | 仍由既有 SDK 重试决策处理，不由 transport 隐式重试 |

该矩阵是 HE-7 的系统验收门槛，不要求每种语言在单元测试中自行实现 DNS 或 socket 故障框架。本地
loopback/自定义 DNS 测试只能验证客户端算法；上线前仍需在 staging 的真实 DNS、路由、NetworkPolicy、代理和
TLS 环境逐语言复验。

## 自动化证据

- `run-sdk-happy-eyeballs-gate.sh` 启动共用 loopback fixture，使用已饱和 accept queue 让 TCP connect
  真实挂起，而不是让 mock 方法直接 sleep。五语言均通过 IPv4-only、IPv6-only、双栈均可达、IPv6 黑洞、
  IPv4 黑洞和全部地址黑洞六种场景。
- 每种语言调用自身生产 HTTP transport；测试只注入 A/AAAA 返回顺序。夹具统一核对成功场景各收到 5 次请求、
  全黑洞收到 0 次，防止地址竞速导致非幂等 POST 重复。
- Go 同时守住显式 `FallbackDelay=250ms` 和原有 10s 默认建连预算；TypeScript 继续断言 Agent 参数真实传到
  Node 连接层；Python/Rust 在当前可安装依赖集合上执行；Java 同时验证 `stop(timeout)` 会取消挂起调用。

当前本地证据仍不冒充 HE-7：loopback 黑洞证明了真实 socket 建连竞速和总超时，但 DNS 顺序是测试注入，未覆盖
staging 的权威 DNS、跨主机路由、NetworkPolicy、TLS 出口代理和 MTU。上述外部环境组合仍须在上线环境留档。
