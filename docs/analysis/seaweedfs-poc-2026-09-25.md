# SeaweedFS S3 Compatibility POC

## Result

**Passed for the tested single-process `weed mini` S3 contract.** This is a local compatibility POC, not production approval, HA evidence, or a capacity benchmark.

- Repository revision: PR branch `feature/s3-compatible-object-store-governance`
- Image: `chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882`
- Topology: one `weed mini` container with an isolated temporary volume; S3, master and Admin UI ports bind to loopback only
- Authentication: random `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`; anonymous root request returned HTTP 403
- Application client: repository `S3ObjectStore` using AWS SDK for Java v2
- Result: 3 live checks passed (application contract, invalid-credential rejection, and aligned mixed-load smoke test)

## Operations Verified

The shared live S3 contract exercised bucket creation, PUT/GET/HEAD/existence, range GET, server-side copy, paginated listing, batch delete, presigned PUT/GET using real HTTP requests, and multipart upload above the configured 8 MiB threshold with full readback. Invalid credentials were rejected with HTTP 403.

Additional isolated checks passed:

- **Restart persistence:** an 8 MiB generated object was uploaded to a temporary named volume, the container was restarted, and the downloaded SHA-256 matched.
- **Aligned MinIO migration check:** `mc mirror` copied 32 generated objects sized 1–32 KiB (589,824 bytes total) from an isolated MinIO bucket. The local source manifest, MinIO readback, and SeaweedFS readback SHA-256 manifests matched.
- **Aligned pressure smoke:** four concurrent workers ran for 120 seconds with the same Java `S3ObjectStore` and 1 MiB payload as RustFS. It completed 7,896 PUTs and 7,896 GETs (15,792 operations, 131.56 operations/second), with full readback equality and zero failed operations. This is an object-level pressure smoke on one local `weed mini` process, not a production capacity benchmark.

The official image exposes the Admin UI at container port 23646, and the local POC maps it to a randomized loopback port. The UI itself was not browser-tested and is not part of the S3 contract result.

## Gaps Before Production

- `weed mini` is an all-in-one single-node mode; no multi-master, multi-filer, replicated volume, node/disk failure, recovery, rolling upgrade, or production failover was tested.
- No production-representative capacity test, object-count scaling, resource profile, or latency distribution was measured. The short mixed load above is only a functional smoke test.
- No full worker/business E2E was run with SeaweedFS. The existing E2E harness hard-wires MinIO, and its export-content verifier can silently skip content assertions on storage read errors; it is not valid candidate-backend evidence without a strict external-S3 test path.
- No bucket-specific least-privilege policy, credential rotation, TLS certificate validation/rotation, retention/versioning/object-lock, audit integration, or backup/restore drill was evaluated.
- Only generated-object copying was tested. Application-wide data transfer, metadata/version parity, migration cutover, and rollback were not tested.

## Recommendation

SeaweedFS is viable for a further isolated staging evaluation of the application's S3 usage. Keep MinIO as the default backend. Before production consideration, test the intended distributed topology, IAM scope, TLS, backup/restore, migration procedure, and representative worker flows. Do not infer production readiness from the single-process `mini` result.

## Reproduction

```bash
scripts/local/seaweedfs-poc.sh

S3_COMPAT_POC_LOAD_SECONDS=120 \
S3_COMPAT_POC_LOAD_WORKERS=4 \
S3_COMPAT_POC_LOAD_PAYLOAD_BYTES=1048576 \
  scripts/local/seaweedfs-poc.sh
```

The script requires Docker, `curl`, `openssl`, and the repository Maven wrapper. It uses the pinned image above; override `SEAWEEDFS_POC_IMAGE` only when intentionally testing a different image.

## References

- [SeaweedFS official project and Docker quick start](https://github.com/seaweedfs/seaweedfs)
- [SeaweedFS Amazon S3 API and authentication](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API)
- [SeaweedFS 4.47 release](https://github.com/seaweedfs/seaweedfs/releases/tag/4.47)
- [Aligned RustFS and SeaweedFS comparison](./s3-compatible-backend-comparison-2026-09-25.md)
