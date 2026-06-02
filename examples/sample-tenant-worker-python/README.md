# sample-tenant-worker-python

Python counterpart to [`examples/sample-tenant-worker/`](../sample-tenant-worker/) (Java).
Demonstrates the minimal tenant worker integration using
[`batch-worker-sdk`](../../sdk-python/) and the `@batch_task` decorator.

## Quickstart

```bash
# 1. Editable-install the SDK (from the repo root):
pip install -e sdk-python

# 2. Editable-install this sample:
pip install -e examples/sample-tenant-worker-python

# 3. Run (requires the env vars below):
export BATCH_SDK_BASE_URL=http://localhost:8081
export BATCH_SDK_TENANT_ID=acme
export BATCH_SDK_WORKER_CODE=acme-sample-py-1
python -m sample_tenant_worker
```

## Registered handlers

| `task_type` | Behaviour |
| --- | --- |
| `sample-echo` | Echoes `parameters` back as the result `output`. |
| `sample-sleep` | Sleeps `parameters.millis` then returns `{slept: millis}`. |

Both handlers are registered via `@batch_task` at import time and
collected by the entry point through `collect_registered_handlers()`.

## Testing locally without an orchestrator

Use `batch_worker_sdk.testkit.FakeBatchPlatform`:

```python
async with FakeBatchPlatform() as fp:
    cfg = BatchPlatformClientConfig(base_url=fp.base_url, ...)
    # drive your handler...
```

See `sdk-python/tests/testkit/test_fake_platform.py` for end-to-end
patterns.
