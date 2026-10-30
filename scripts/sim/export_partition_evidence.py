# 用途：校验 SIM 导出分片文件的租户及业务记录完整性。
# 用法：由 SIM 分片场景调用 verify_partition_contents，读取器负责取得对象内容。
"""按固定 SIM 业务键核对跨分片无重无漏。"""

import json
from collections import Counter


def verify_partition_contents(objects, expected_partitions, expected_rows, read_object):
    """核对每个 JSON 产物的 details 与固定测试夹具。"""
    if len(objects) != expected_partitions or len(set(objects)) != expected_partitions:
        raise ValueError("export objects must be distinct and match partition count")

    actual = Counter()
    for object_path in objects:
        document = json.loads(read_object(object_path))
        details = document.get("details") if isinstance(document, dict) else None
        if not isinstance(details, list):
            raise ValueError("export details must be a JSON array")
        for row in details:
            if not isinstance(row, dict) or row.get("tenant_id") != "ta":
                raise ValueError("export row has an unexpected tenant")
            actual[row.get("customer_no")] += 1

    duplicates = [key for key, count in actual.items() if count != 1]
    if duplicates:
        raise ValueError(f"duplicate export business keys: {duplicates}")
    expected = {f"EXP3B-{number:04d}" for number in range(1, expected_rows + 1)}
    missing = expected - actual.keys()
    extra = actual.keys() - expected
    if missing or extra:
        raise ValueError(f"export keys mismatch: missing={len(missing)}, unexpected={len(extra)}")
