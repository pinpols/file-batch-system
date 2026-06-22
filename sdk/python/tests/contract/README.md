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

## Status: enforcing (2026-06-22)

**Hard.** The runner feeds each fixture's input through the real Python
decision core and asserts the output against `expected` byte-for-byte.
Every fixture is a real assertion — no `xfail`. A mismatch fails the
`python contract + shared-constants parity` job. `pyproject.toml` sets
`xfail_strict = true`, so any leftover `xfail` marker that actually
passes (xpass) is also treated as a failure → no silent staleness.

If Lane N has not landed the fixtures yet, the runner gracefully
reports `0 fixtures discovered` and the job stays green — discovery is
decoupled from Lane N's merge order.
