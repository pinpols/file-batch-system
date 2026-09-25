# RustFS and SeaweedFS Aligned S3 Validation

## Scope

Both candidates were exercised through the same repository Java `S3ObjectStore` client and the same live test class. Each POC used its pinned image, an isolated temporary volume and credentials, and loopback-bound ports. The existing MinIO service was used only as the source for uniquely named temporary migration buckets; no existing application bucket was enumerated or copied.

## Results

| Check | RustFS | SeaweedFS |
|---|---|---|
| Image | `rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff` | `chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882` |
| Live application checks | 3 passed: S3 contract, invalid credentials rejected (403), mixed load | 3 passed: same checks |
| 8 MiB persistence across container restart | SHA-256 matched | SHA-256 matched |
| MinIO object migration | 32 objects, 589,824 bytes; source, MinIO readback, and target SHA-256 manifests matched | Same fixture size/count and hash checks passed |
| 120-second local pressure smoke | 4 workers, 1 MiB PUT then GET: 7,103 PUT + 7,103 GET; 14,206 operations; 118.34 ops/s; zero failures | Same workload: 7,896 PUT + 7,896 GET; 15,792 operations; 131.56 ops/s; zero failures |

Each mixed-load iteration writes an object and reads it back, asserting exact byte equality. Any worker exception fails the test. All temporary test objects and migration buckets were cleaned up.

## Interpretation

The same API contract, restart persistence, and small MinIO-to-target copy workload passed for both. The local 120-second pressure smoke also completed without errors for both. The observed operations/second values are **not a performance ranking**: this was one sequential run per candidate on a developer machine, without controlled CPU/disk/network allocation, repeated trials, latency percentiles, or production-sized data.

Distributed failure handling is not aligned or validated. RustFS officially documents MNMD mode with four or more servers and cross-server erasure coding. The earlier local RustFS probe used containers sharing one host's physical disk and bypassed its disk-safety check; its `CopyObject` HTTP 500 is inconclusive. SeaweedFS was run as one `weed mini` process, not a distributed topology. These results cannot compare HA behavior.

No full worker/business E2E, representative capacity test, TLS/IAM least-privilege validation, key rotation, backup/restore, or production failure drill was completed. The existing E2E test harness is MinIO-specific and is not evidence for either candidate.

## Conclusion

Both products pass the currently aligned local S3 client checks and are suitable for further isolated staging evaluation. The evidence does not establish production readiness or justify selecting one on performance or HA grounds. Keep MinIO as the current default until both candidates are tested against equivalent, valid deployment topologies and the real worker/business flow.

## Related Reports

- [RustFS POC](./rustfs-poc-2026-09-25.md)
- [SeaweedFS POC](./seaweedfs-poc-2026-09-25.md)
