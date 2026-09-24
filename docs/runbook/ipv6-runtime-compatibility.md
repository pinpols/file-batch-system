# IPv6 运行时兼容约束

本文定义本地脚本、裸 JVM、Docker Compose 和外部依赖接入 IPv6 时的地址格式，避免把 IPv6 的冒号误当成端口分隔符。

## 规则

| 场景 | 正确格式 | 说明 |
|---|---|---|
| PostgreSQL/Redis 的独立 host 参数 | `2001:db8::20` | `PGHOST`、`REDIS_HOST` 传裸地址，不加方括号 |
| JDBC、HTTP、MinIO URL | `http://[2001:db8::20]:19000` | URL authority 中必须使用方括号 |
| Kafka bootstrap server | `[2001:db8::20]:19092` | Kafka 客户端使用 `host:port` authority 格式 |
| SFTP 独立 host/port | `SFTP_HOST=2001:db8::20`、`SFTP_PORT=22` | 不把两个字段预先拼成一个未加括号的字符串 |
| Docker Compose 服务间通信 | `kafka:29092`、`postgres:5432` | 优先使用 Docker DNS 服务名，不依赖宿主 IPv6 路由 |

## 脚本约定

公共脚本库 [`scripts/lib/env-common.sh`](../../scripts/lib/env-common.sh) 提供：

- `batch_format_host_port host port`：生成 URL/Kafka 所需的 authority，兼容裸 IPv6、已加括号 IPv6、IPv4 和 hostname。
- `batch_parse_host_port value`：解析单个 bootstrap authority，支持 `[IPv6]:port`；裸 IPv6 不会被误解析成带端口地址。

脚本中不得重新实现 `${value%%:*}` 或 `${value##*:}` 形式的 host/port 拆分。数据库客户端参数必须继续分别传递裸 `host` 和 `port`。

## 网络与安全边界

- 开启 IPv6 不等于放宽 SSRF。`DnsResolveGuard` 仍拒绝回环、链路本地、ULA（`fc00::/7`）、IPv4-mapped IPv6 及受限 IPv4 网段。
- Webhook、Atomic HTTP、Dispatch HTTP 等出站连接必须保留按解析地址的安全校验；不能仅用字符串前缀判断 IPv6 是否为私网。
- Compose 默认仍使用现有 IPv4 端口映射和服务名，IPv6 Compose 网络需由部署环境显式启用，不在应用启动时自动创建或切换网络。
- `localhost` 是本地开发默认值，不代表固定 IPv4；需要固定协议族时由部署环境使用 `127.0.0.1` 或 `[::1]` 明确指定。
- Java 运行时统一注入 `-Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=false`：保留双栈，不强制禁用 IPv6；当 DNS 同时返回 IPv4/IPv6 时优先 IPv4。容器由 `deploy/docker/entrypoint.sh` 统一追加，裸 JVM 和 Sim 由公共变量 `BATCH_JVM_NETWORK_OPTS` 注入。
- 这只是地址选择偏好，不是连接失败降级策略；连接超时、重试和业务错误仍由 HTTP 客户端/调用方处理。对端单栈 IPv6 时 JVM 仍可使用 IPv6，对端仅 IPv4 时保持既有 IPv4 连接。
- 平台自有 Java 外部 HTTP 逐步统一到 [`OutboundHttpTransport`](../../batch-common/src/main/java/io/github/pinpols/batch/common/http/OutboundHttpTransport.java)：业务层只依赖窄契约，Console/Orchestrator 由应用级 OkHttp 5 适配器提供双栈连接能力。实施边界见 [`IPv6 Happy Eyeballs 渐进落地方案`](../plans/ipv6-happy-eyeballs-rollout-2026-09.md)。
- 上述 JVM 地址偏好只约束 JDK 默认连接栈。OkHttp 5 开启 `fastFallback` 后会把 A/AAAA 候选交错为 IPv6-first，并在首连接未完成约 250ms 后竞速 IPv4；因此不能用 `preferIPv6Addresses=false` 推断 OkHttp 的首连接地址族。
- 租户可配置地址使用 `GUARDED`，任一 A/AAAA 结果受限即整体拒绝；Alertmanager/OpenLineage 等受控运维地址使用 `TRUSTED`，以兼容 ClusterIP 和 Compose 私网服务名。不得把租户 URL 标为 `TRUSTED`。
- `GUARDED` 同样校验 IPv4/IPv6 字面量，并固定使用通过校验的单次 DNS 地址快照；该路径禁用系统代理，防止代理端二次解析形成 DNS rebinding。需要企业出口代理时应新增独立的可信代理配置，不得直接给租户 URL 恢复系统代理。
- SSRF 受限范围除私网、回环和链路本地外，还包括 `0.0.0.0/8`、`100.64.0.0/10`、`198.18.0.0/15`、文档/协议保留 IPv4 网段及其 IPv4-mapped IPv6 形式。
- OkHttp 的连接回退不等于业务重试。通用 transport 关闭隐式连接重试和重定向，业务重试、幂等判断仍归短信、通知、Worker 等原领域所有。

## 验证

最小脚本回归：

```bash
bash -n scripts/lib/env-common.sh scripts/sim/env-lan.sh \
  scripts/local/health-check-infra.sh scripts/lib/sdk-e2e-common.sh
```

使用 LAN 或外部依赖时示例：

```bash
LAN_HOST=2001:db8::20 source scripts/sim/env-lan.sh
PGHOST=2001:db8::20 PGPORT=5432 bash scripts/local/health-check-infra.sh
KAFKA_BOOTSTRAP='[2001:db8::20]:19092' bash scripts/ci/run-sdk-live-transport-gate.sh
```

以上示例要求目标服务本身已监听 IPv6，文档中的 `2001:db8::/32` 仅为保留文档地址，不可作为真实目标。
