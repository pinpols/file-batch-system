# IPv6 Happy Eyeballs 渐进落地方案

> 状态：HE-1 ~ HE-6 已完成本地实现和对应层级验证；HE-7 的全网络矩阵待生产同构环境验证。本文是外部 HTTP 双栈治理的边界和验收依据，不授权全工程直接替换 HTTP 客户端。

## 目标

在不改变内部服务通信、Worker 业务重试和 SDK 核心依赖边界的前提下，让平台自有 Java 外部 HTTP 调用具备：

- A/AAAA 完整解析与双栈连接尝试；
- 租户可配置目标的 SSRF fail-closed；
- 运维固定目标对 Kubernetes/Compose 私网地址的兼容；
- 统一超时、禁止隐式重定向和隐式连接重试；
- 业务代码不依赖 OkHttp 类型。

## 架构边界

```text
业务 service/provider/policy
          |
          v
OutboundHttpTransport            batch-common:纯 JDK 窄契约
          |
          +-- OkHttpConsoleExternalHttpTransport
          +-- OkHttpOrchestratorExternalHttpTransport
                         应用基础设施适配器:OkHttp 5
```

`batch-common` 不引 OkHttp。Console 和 Orchestrator 各自拥有适配器，是为了保持模块依赖方向，而不是复制业务策略。

地址策略分为两类：

| 策略 | 使用场景 | DNS/SSRF 语义 |
|---|---|---|
| `GUARDED` | 租户 URL、验证码、短信、HTTP sensor、dry-run 探针 | 域名和 IP 字面量都先校验；任一地址受限则整体拒绝；冻结同一地址快照直连并禁用系统代理 |
| `TRUSTED` | Alertmanager、OpenLineage 等运维固定地址 | 使用系统 DNS 完整结果，允许 ClusterIP/Compose 私网；目标只能来自受控配置 |

## 明确不改

- Docker/Kubernetes 内部服务名、PG、Kafka、Valkey、MinIO 连接策略不改。
- `worker-core` 平台内部 HTTP 客户端不在本轮迁移；它有独立重试和协议契约。
- Atomic/Dispatch 已有 OkHttp 实现继续保留其响应上限、幂等重试、回执和领域错误映射，不套通用适配器。
- Java SDK core 保持 Spring-free 和单一制品；内部使用 OkHttp 5，不自研网络栈、不新增平行 SDK artifact、不暴露 OkHttp 公共类型。
- Go/Python/Rust/TypeScript SDK 使用各语言原生连接栈，不为了形式统一引入 OkHttp 等价层。

## 分阶段实施

| ID | 内容 | 状态 | 验收 |
|---|---|---|---|
| HE-1 | 建立 `OutboundHttpTransport` 窄契约和应用级 OkHttp 适配器 | 已完成 | 业务类不出现 OkHttp 类型；适配器方法、策略和响应上限有测试 |
| HE-2 | 迁移 Console/Orchestrator 中原 JDK HttpClient、简单 RestClient 的外部调用 | 已完成 | 原有 provider/sensor/告警/lineage 单测通过 |
| HE-3 | `DnsResolveGuard` 增加全部地址校验 | 已完成 | 顺序、去重、混合公网/私网拒绝、IPv4/IPv6 受限地址有测试 |
| HE-4 | 现有 OkHttp SSRF DNS 返回完整安全地址列表 | 已完成 | Webhook、Dispatch、Atomic 的领域实现不重写；原定向测试通过 |
| HE-5 | 双栈故障注入验证 | 已完成（本地） | IPv6 不可达/IPv4 可达、IPv4 不可达/IPv6 可达、双栈均可达三组证据 |
| HE-6 | SDK 控制面 HTTP 双栈治理 | 已完成（本地） | 五语言通过真实 loopback socket 的单栈、双栈、双向黑洞和全黑洞矩阵；Java 在现有 core 内使用 OkHttp 5，并验证单次 POST 和停机取消；不把仓库锁文件表述成下游依赖锁定 |
| HE-7 | 生产同构网络验证 | 外部阻塞 | staging DNS、路由、NetworkPolicy 和出口代理环境验收 |

## 安全不变量

1. `GUARDED` 不能只验证第一个 DNS 结果，也不能过滤危险地址后继续连接。
2. DNS 校验返回的地址列表必须冻结后直接交给连接层，禁止验证后恢复系统 DNS；IP 字面量也必须走相同校验。
3. `GUARDED` 必须使用 `Proxy.NO_PROXY` 直连已校验地址，避免系统代理在代理端重新解析目标域名。需要企业出口代理的目标必须使用单独的可信代理策略，不能削弱租户 URL 的 SSRF 边界。
4. 重定向默认关闭，避免跳转目标绕过原始 URL 的地址策略。
5. 通用 transport 不做业务重试；短信、通知、Worker 等重试仍由原领域策略负责。
6. 响应体设置 1 MiB 上限；超过上限按 IO 失败处理，避免外部响应占满堆。
7. `allow-private` 仅用于本地联调，不能放行 any-local 和 multicast。

## 验证矩阵

| 维度 | 本地自动化 | staging |
|---|---|---|
| IPv4-only 目标 | 单元/适配器测试 | 必测 |
| IPv6-only 目标 | 地址与 URI 测试 | 必测 |
| 双栈正常 | A/AAAA 顺序与完整列表测试 | 必测 |
| IPv6 黑洞、IPv4 正常 | 需要可控网络故障注入 | 必测 |
| IPv4 黑洞、IPv6 正常 | 需要可控网络故障注入 | 必测 |
| 公网+私网混合 DNS | 单元测试整体拒绝 | 必测 |
| 重定向到受限地址 | 客户端默认禁止重定向 | 抽查 |

本地单元测试只能证明地址策略和适配器契约，不能替代真实操作系统、DNS、路由和出口代理下的 Happy Eyeballs 时延证据。

## 本地验证记录

- 最新安全收口定向测试：87 个测试通过，0 失败、0 跳过。
- `OutboundHttpRequestTest`：4 个测试通过，覆盖不可变请求、协议/超时/URI host 校验和日志脱敏。
- 受影响模块编译、PMD、Spotless：通过。
- 模块依赖边界、应用治理、配置治理、环境变量治理、文档结构和文档引用守护：通过。
- 受影响模块扩展测试首次被复用 MinIO 中 16 个历史 orphan 测试对象阻断；仅清理该测试前缀后，失败的 `FileGovernanceIntegrationTest` 定向复跑通过，未修改生产逻辑。
- Orchestrator/Console 真实 ApplicationContext 启动 IT 均通过，并断言各上下文只有对应的 OkHttp transport 实现。
- HE-5 使用自定义 DNS、双栈 loopback 和可取消黑洞 socket 验证：IPv6 黑洞回退 IPv4 约 270ms，IPv4 不可达但 IPv6 可用约 73ms，双栈均可用约 9ms。
- GUARDED 适配器测试验证了 IP 字面量在建连前被拒绝、DNS 地址快照只解析一次，并且系统 `ProxySelector` 不会介入连接。
- HE-5 是客户端进程内的确定性故障注入；HE-7 仍必须在 staging 记录真实 DNS、操作系统路由、NetworkPolicy 和出口代理下的切换时延。
- HE-6 的五语言现状、Java 适配边界和验收矩阵见 [`SDK IPv6 / Happy Eyeballs`](../sdk/ipv6-happy-eyeballs.md)。
- SDK 单元测试只承担各语言可控的算法/配置回归；纯 IPv6、反向黑洞、全部地址不可达、真实代理与
  NetworkPolicy 组合原归 HE-7；其中前三项现已由五语言真实 loopback socket 门禁覆盖，真实 DNS、跨主机路由、
  TLS 出口代理和 NetworkPolicy 仍归 HE-7，不以本地结果冒充生产同构证据。
