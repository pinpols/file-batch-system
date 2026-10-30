# batch-worker-sdk-go

Go BYO worker SDK decision core + conformance runner for the file-batch-system
control plane. It mirrors the TypeScript reference (`../typescript`)
1:1 in behavior: pure, IO-free decision functions (`protocol/decide.go`) that map a
protocol input (HTTP status, heartbeat directive, lease renew, capacity / stop
signal) to a `Decision` whose fields are the closed `then.expect` vocabulary of the
contract fixtures, plus the cross-language shared constants kept honest against
`docs/api/sdk-shared-constants.yaml`. Zero external module dependencies (stdlib only);
the conformance runner drives all `docs/api/sdk-contract-fixtures` by routing off
each fixture's `when` shape (never `then.expect`).

**最低环境要求**:**Go 1.26+**(`go.mod` go directive)。CI 覆盖最低支持的 Go 1.26 和当前稳定版 Go 1.27。核心 stdlib-only 零依赖;Kafka 适配器是独立 nested module(`kafka/`,依赖 `segmentio/kafka-go`),不用 Kafka 则不引入。

## Test

```sh
go test ./...
```

## P1 retry / idempotency

The SDK stays thin: retry and idempotency are explicit handler decorators, not
business templates and not framework wiring.

```go
store := client.NewInMemoryIdempotencyStore()
handler := client.WithRetry(
    client.WithIdempotency(myHandler, store, time.Hour, client.TaskIDIdempotencyKey),
    client.DefaultRetryPolicy(),
)
```

Production idempotency stores implement `SdkIdempotencyStore`; the SDK ships only
`NoopIdempotencyStore` and `InMemoryIdempotencyStore`.

## 实际执行入口计时

默认 `Worker.dispatch` 在实际进入业务处理器前，通过原续租端点提交 `details.executionStarted=true`，使用 CLAIM 返回的当前 invocation。确认失败或租约失效时不调用业务、不伪造业务失败回报；明确取消则保留取消结果。计时信号与非幂等副作用开始标记独立，处理器参数和业务批处理逻辑保持原契约。详见 [通讯协议](../../docs/sdk/wire-protocol.md#实际执行入口计时)。

### 消费背压与已拉取记录

达到并发上限后, Worker 保留当前已经拉取的记录, 等待原有低水位判定恢复后优先认领它,
再继续获取后续记录。Pause/Resume 本身不能让已拉取记录自动重读; 提交不能跨过未处理消息。
停机或取消会退出容量等待, 原记录保持未提交。真实 Kafka 回归位于 `kafka/kafka_consumer_integration_test.go`。
