# batch-worker-sdk (Rust)

Rust BYO worker SDK decision core + runtime engine + conformance runner for the
file-batch-system control plane. It mirrors the Go (`../go`) and TypeScript
(`../typescript`) reference implementations 1:1 in behavior: pure, IO-free decision
functions (`src/decide.rs`) that map a protocol input (HTTP status, heartbeat
directive, lease renew, capacity / stop signal) to a `Decision` whose fields are the
closed `then.expect` vocabulary of the contract fixtures, plus the cross-language
shared constants kept honest against `docs/api/sdk-shared-constants.yaml`.

The default build has **zero external dependencies** (std-only); the conformance
runner drives all 12 `docs/api/sdk-contract-fixtures` by routing off each fixture's
`when` shape (never `then.expect`).

## Layout

```
src/
  decide.rs / protocol.rs / constants.rs   decision core + wire types + shared constants
  client/                                  runtime engine (transport / scheduler / lifecycle
                                           / consumer / sensitive / handler / testkit)
  kafka.rs                                 real Kafka consumer adapter — feature-gated `kafka`
tests/
  conformance.rs                           drives the 12 contract fixtures' then.expect
  constants_parity.rs                      asserts constants == sdk-shared-constants.yaml
  runtime.rs                               runtime engine integration tests
```

## Test

```sh
cargo test                 # decision core + runtime + conformance + parity (zero-dep)
cargo check --features kafka   # compile the rdkafka adapter (needs cmake + libssl)
```

The `kafka` feature pulls `rdkafka` (real SASL/SCRAM consumer); it is optional so the
core stays dependency-free. The end-to-end Kafka integration test is env-gated
(`KAFKA_BOOTSTRAP`) and runs only against a live broker / in CI.

## Conformance contract

This SDK must satisfy `docs/sdk/byo-conformance-contract.md`: consume
`docs/api/sdk-shared-constants.yaml` (no re-authored literals), pass every fixture's
machine-assertable `then.expect`, and plug into `.github/workflows/sdk-contract-parity.yml`
(`rust-contract` job). Protocol authority: `docs/sdk/wire-protocol.md` §A/§B/§C.
