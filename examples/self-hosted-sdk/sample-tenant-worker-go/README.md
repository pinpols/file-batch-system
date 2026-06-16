# sample-tenant-worker-go — Go BYO SDK 示范

租户自托管 Worker 的最小可运行 Go 示范,直接用 [BYO SDK](../../docs/sdk/byo-sdk-guide.md)
(`sdk/go`)手写 `main` wiring。业务方复制本目录到自己 repo,把 `echoHandler`
换成业务实现即可。

> 这是 Go 语言的 BYO(bring-your-own)接入,对照其它语言:
> - [`../sample-tenant-worker-java`](../sample-tenant-worker-java/) — Java + 手写 main
> - [`../sample-tenant-worker-java-spring`](../sample-tenant-worker-java-spring/) — Java + Spring Boot starter
> - [`../sample-tenant-worker-python`](../sample-tenant-worker-python/) — Python 3.12+ asyncio

## 它做什么

1. 从环境变量读取配置 + 凭据(凭据**只走 env**,绝不从消息体读),缺必填变量时一次性列全并 fail-fast。
2. 构建 HTTP 控制面 transport(`client.NewHTTPTransport`),经自定义 `RoundTripper` 注入 `Authorization: Bearer <API_KEY>`(SDK 无内置 API-key 选项),保留 SDK 强制的 10s 超时。
3. 构建真实 Kafka 消费者(`kafka.NewConsumer`,嵌套模块):无 SASL 变量时走 PLAINTEXT,两个 SASL 变量都设置时走 SASL/SCRAM-SHA-512。
4. 注册一个 echo 风格 `TaskHandler`:打日志 + 把 `effectiveConfig` 原样回吐到 `Outputs`,对齐 Java/Python 的 `echo` sample。
5. `worker.RunUntilSignal(ctx)`:阻塞到 SIGINT/SIGTERM,然后 `Stop(30s)` 优雅排空。

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `BATCH_BASE_URL` | ✅ | 控制面 base URL,如 `https://batch.example.com` |
| `BATCH_API_KEY` | ✅ | 控制面 API key(注入为 `Authorization: Bearer`) |
| `BATCH_TENANT_ID` | ✅ | 租户 ID,作 Kafka 主题前缀 + 消费组 + §1.9 自检 |
| `BATCH_WORKER_CODE` | ✅ | worker 标识,如 `xyz-sample-go-1` |
| `KAFKA_BOOTSTRAP` | ✅ | broker 列表,逗号分隔,如 `kafka.example.com:9092` |
| `KAFKA_SASL_USERNAME` | ⬜ | 设置后启用 SASL/SCRAM-SHA-512(须同时设 password) |
| `KAFKA_SASL_PASSWORD` | ⬜ | 同上 |

> **凭据纪律**:SASL 密码 / API key 等敏感值只走进程环境变量,绝不进消息参数或 register body —— SDK 的 `SensitiveValidator`(§1.8)会在 register / 派单两处拦截。

## 跑

```bash
# Go 工具链(本机 PATH 上的 go GOROOT 坏掉,显式指定):
export GOROOT=/usr/local/opt/go/libexec && export PATH="$GOROOT/bin:$PATH"

export BATCH_BASE_URL=https://batch.example.com
export BATCH_API_KEY=...
export BATCH_TENANT_ID=tenant-xyz
export BATCH_WORKER_CODE=xyz-sample-go-1
export KAFKA_BOOTSTRAP=kafka.example.com:9092
# 可选 SASL:
# export KAFKA_SASL_USERNAME=...
# export KAFKA_SASL_PASSWORD=...

GOROOT=/usr/local/opt/go/libexec go run .
```

> 实际连接需要**活的控制面 + broker**(`kafka.NewConsumer` 启动即做主题发现,
> 没有匹配的 `batch.task.dispatch.<tenant>.*` 主题或 broker 不可达会直接报错)。
> 离线只能验证编译:

```bash
GOROOT=/usr/local/opt/go/libexec go build ./...
GOROOT=/usr/local/opt/go/libexec go vet ./...
```

## 验证

控制台触发一个 `taskType` Job,看 worker 日志出现:

```
INFO echo handler taskId=N traceId=... params={...}
```

## 业务方落地

复制本目录,把 `echoHandler.Execute` 换成业务逻辑;长任务记得在循环里轮询
`tc.Cancellation.IsCancellationRequested()` 做协作式取消。
