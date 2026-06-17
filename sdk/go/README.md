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
