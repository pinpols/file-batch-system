# RustFS S3 Compatibility POC

## Result

**Passed for the tested single-node S3 API contract and limited object-level operations.** A local multi-node failure probe did not pass the application contract after one node was stopped. This is not approval for production replacement or HA deployment.

- Repository revision: PR branch `feature/s3-compatible-object-store-governance`
- RustFS image: `rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff`
- Application client: existing `S3ObjectStore` using AWS SDK for Java v2
- Execution: local Docker, single RustFS node, randomized credentials, isolated temporary Docker volume
- Result: 3 live checks passed against single-node RustFS; S3 API and Console health endpoints became ready

## Operations Verified

- Bucket auto-creation and small-object PUT, GET, HEAD/size and existence checks
- Range GET from a non-zero offset
- Server-side object copy
- Prefix listing with pagination markers
- Batch delete
- Presigned PUT and GET, including an actual HTTP upload and download
- Multipart upload above the configured 8 MiB threshold, followed by full-content readback
- Invalid access/secret credentials are rejected with HTTP 403
- An aligned 120-second local pressure run used the same Java `S3ObjectStore`, four workers, and a 1 MiB payload as the SeaweedFS run. It completed 7,103 PUTs and 7,103 GETs (14,206 operations, 118.34 operations/second) with full readback equality and zero failed operations.

## Additional Local Validation

- **Container restart persistence: passed.** Uploaded an 8 MiB object to a temporary named volume, restarted the same RustFS container, and verified the downloaded object's SHA-256 was unchanged. This does not cover host power loss, disk failure, backup restore, or PITR.
- **MinIO-to-RustFS object-level copy: passed.** Copied one generated 8 MiB object between isolated temporary buckets with `mc` and verified matching SHA-256 after download. No existing application bucket was enumerated or copied. This does not test migration of an entire deployment, object-lock/versioning/metadata parity, cutover, or rollback.
- **Aligned MinIO migration check: passed.** Mirrored the same workload shape used for SeaweedFS: 32 generated objects sized 1–32 KiB (589,824 bytes total) from isolated temporary MinIO buckets. The local source manifest, MinIO readback, and RustFS readback SHA-256 manifests matched. No existing application bucket was accessed.
- **Multi-node process-failure probe: not passed.** Four RustFS 1.0.0 containers reached the health endpoint. The pinned image refused the first run because all locally mounted volumes resolved to the same physical device. A second run used the image's explicitly local-test-only `RUSTFS_UNSAFE_BYPASS_DISK_CHECK=true` switch and initialized volumes owned by UID 10001. The full application S3 contract passed with all four processes up. After stopping node 3, node 0 still returned a healthy probe, but `CopyObject` returned HTTP 500 on all SDK retries; the same result reproduced. Container logs did not provide a more specific cause. Because this setup had no independent hosts or disks and bypassed disk validation, this is a failed local fault-injection probe, not evidence of behavior on a valid MNMD topology.
- **Production capacity/load and full worker/business E2E: not run.** The 120-second load is a local object-level pressure smoke test, not a production capacity benchmark. This POC does not route the complete application runtime or worker scenarios to RustFS.

The POC script removes its container and temporary Docker volume on exit. It binds dynamically allocated ports to loopback only. The regular MinIO compose service, test container image, production configuration and HA manifests were not changed.

## Gaps Before Production

- No valid multi-host/multi-disk MNMD test, disk failure, recovery, rolling upgrade, or production durability drill
- No production-representative sustained load, large-scale object-count, resource, or latency measurement
- No non-root IAM/service-account policy scope, key rotation, TLS certificate validation/rotation, SSE/KMS, audit, or retention evaluation
- No migration test from a MinIO data directory; S3 API object copy does not establish on-disk compatibility or full deployment migration correctness
- No full worker/business E2E using RustFS; the application check exercises `S3ObjectStore` directly

## Recommendation

RustFS remains a candidate for an isolated staging pilot only. Keep MinIO as the default test backend and do not route production data to RustFS until the failed/unverified checks above—especially valid multi-host recovery, load, IAM/TLS/KMS controls, and application-level E2E—are completed against the intended deployment topology. Any migration must use an explicit copy-and-verify plan with rollback; do not point RustFS at MinIO's existing data directory.

## Reproduction

```bash
scripts/local/rustfs-poc.sh

S3_COMPAT_POC_LOAD_SECONDS=120 \
S3_COMPAT_POC_LOAD_WORKERS=4 \
S3_COMPAT_POC_LOAD_PAYLOAD_BYTES=1048576 \
  scripts/local/rustfs-poc.sh
```

The script requires Docker, `curl`, `openssl`, and the repository Maven wrapper. Override `RUSTFS_POC_IMAGE` only when intentionally testing a different image. It runs the shared `S3CompatibleObjectStorePocTest` contract.

## References

- [RustFS container deployment](https://docs.rustfs.com/en/installation/container)
- [RustFS installation modes](https://docs.rustfs.com/en/installation)
- [Aligned RustFS and SeaweedFS comparison](./s3-compatible-backend-comparison-2026-09-25.md)
