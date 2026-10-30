# 用途：根据完整 Semgrep 报告汇总阻断项；扫描错误表示覆盖不完整。
# 用法：由 security-scan 编排器加载并传入报告路径，不作为独立入口。

import json
import sys


try:
    with open(sys.argv[1], encoding="utf-8") as report_file:
        report = json.load(report_file)
    findings = report["results"]
    errors = report["errors"]
    if not isinstance(findings, list) or not isinstance(errors, list):
        raise ValueError("results and errors must be lists")
    severities = [finding["extra"]["severity"] for finding in findings]
    blocking = sum(severity in {"ERROR", "HIGH", "CRITICAL"} for severity in severities)
    unknown = sum(severity not in {"ERROR", "HIGH", "CRITICAL", "WARNING", "MEDIUM", "LOW", "INFO"}
                  for severity in severities)
    print(f"Semgrep: {len(findings)} findings, {blocking} blocking, "
          f"{len(errors)} scan errors or warnings, {unknown} unknown severities")
    sys.exit(1 if blocking or errors or unknown else 0)
except (OSError, ValueError, KeyError, TypeError) as exc:
    print(f"Cannot evaluate Semgrep report: {exc}", file=sys.stderr)
    sys.exit(1)
