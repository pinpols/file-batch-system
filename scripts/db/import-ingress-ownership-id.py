#!/usr/bin/env python3
# 用途：计算入站归属范围身份；不输出对象存储端点或凭据。
# 用法：python3 scripts/db/import-ingress-ownership-id.py --bucket BUCKET --prefix ingress/
"""使用现有环境变量读取对象存储端点、命名空间和后端。"""

import argparse
import hashlib
import json
import os
from pathlib import Path


def digest(value: dict[str, str]) -> str:
    data = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="计算入站扫描存储和范围身份")
    parser.add_argument("--bucket", default=os.environ.get("BATCH_S3_BUCKET", ""))
    parser.add_argument("--prefix", default=os.environ.get("BATCH_WORKER_IMPORT_SCANNER_PREFIX", "ingress/"))
    parser.add_argument("--namespace", default=os.environ.get("BATCH_KAFKA_NAMESPACE", ""))
    parser.add_argument("--backend", default=os.environ.get("BATCH_STORAGE_BACKEND", "s3"))
    parser.add_argument("--endpoint", default=os.environ.get("BATCH_S3_ENDPOINT", ""))
    parser.add_argument("--filesystem-root", default=os.environ.get("BATCH_STORAGE_FILESYSTEM_ROOT", "."))
    args = parser.parse_args()

    if not args.bucket.strip():
        parser.error("必须提供 --bucket 或 BATCH_S3_BUCKET")
    backend = args.backend.strip().lower()
    if backend == "filesystem":
        location = os.path.abspath(os.path.normpath(str(Path(args.filesystem_root))))
    else:
        location = args.endpoint.strip().rstrip("/")
        if not location:
            parser.error("S3 后端必须提供 --endpoint 或 BATCH_S3_ENDPOINT")
    storage_id = digest({
        "namespace": args.namespace.strip(), "backend": backend,
        "storage": location, "bucket": args.bucket,
    })
    scope_id = digest({"storage": storage_id, "prefix": args.prefix})
    print(f"storage_id={storage_id}")
    print(f"scope_id={scope_id}")


if __name__ == "__main__":
    main()
