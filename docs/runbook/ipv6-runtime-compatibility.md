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
- Java 运行时统一注入 `-Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=false`：保留双栈，不强制禁用 IPv6；当 DNS 同时返回 IPv4/IPv6 时优先 IPv4。容器由 `docker/entrypoint.sh` 统一追加，裸 JVM 和 Sim 由本地启动脚本默认参数注入。
- 这只是地址选择偏好，不是连接失败降级策略；连接超时、重试和业务错误仍由 HTTP 客户端/调用方处理。对端单栈 IPv6 时 JVM 仍可使用 IPv6，对端仅 IPv4 时保持既有 IPv4 连接。

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
