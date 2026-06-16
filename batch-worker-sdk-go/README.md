# batch-worker-sdk-go

Go BYO worker SDK decision core + conformance runner for the file-batch-system
control plane. It mirrors the TypeScript reference (`../batch-worker-sdk-typescript`)
1:1 in behavior: pure, IO-free decision functions (`protocol/decide.go`) that map a
protocol input (HTTP status, heartbeat directive, lease renew, capacity / stop
signal) to a `Decision` whose fields are the closed `then.expect` vocabulary of the
contract fixtures, plus the cross-language shared constants kept honest against
`docs/api/sdk-shared-constants.yaml`. Zero external module dependencies (stdlib only);
the conformance runner drives all 12 `docs/api/sdk-contract-fixtures` by routing off
each fixture's `when` shape (never `then.expect`).

## Test

```sh
GOROOT=/usr/local/opt/go/libexec go test ./...
```
