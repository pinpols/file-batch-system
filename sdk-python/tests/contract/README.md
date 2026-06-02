# SDK Contract Tests

This directory drives the Python SDK against the **shared contract
fixtures** at `docs/api/sdk-contract-fixtures/` (Lane N). Each fixture
is a JSON file describing one request / response / failure scenario that
*every* SDK implementation (Java + Python + future ones) MUST satisfy
byte-for-byte at the wire level.

## How it works

`test_contract_runner.py` discovers every `*.json` under
`docs/api/sdk-contract-fixtures/`, registers one pytest parameter per
file, and asserts the SDK's behavior against the fixture.

## Phase 0 status

**Stub.** The runner loads fixtures and marks every case `xfail` with
reason `"P0 stub: SDK runtime not implemented yet"`. This proves:

1. The fixture directory is reachable from the SDK CI runner.
2. The count we report matches the count Lane N publishes.
3. Each fixture gets a dedicated pytest node ID so P1+ can flip
   them green one by one with no test-discovery changes.

If Lane N has not landed the fixtures yet, the runner gracefully
reports `0 fixtures discovered` and the entire job stays green — we do
not block Phase 0 on Lane N's merge order.

## Phase 1+ promotion

When `WorkerClient` / `HandlerContext` exist, replace the body of
`run_fixture()` with the real "feed the SDK this input, snapshot the
output, diff against `expected`" logic and drop the `xfail` marker.
