#!/usr/bin/env python3
"""
从 ResultCode.java 枚举生成 docs/dict/error-codes.md。

CI 用法：
    python3 scripts/codegen/gen-error-codes-dict.py --check
        如果生成结果与现有 dict 不一致，exit 1（CI fail）
    python3 scripts/codegen/gen-error-codes-dict.py
        生成 / 覆盖 docs/dict/error-codes.md

不依赖任何第三方库。Python 3.8+ 即可运行。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
SRC = ROOT / "batch-common/src/main/java/io/github/pinpols/batch/common/enums/ResultCode.java"
OUT = ROOT / "docs/dict/error-codes.md"

ENUM_LINE = re.compile(
    r'^\s*(?P<name>[A-Z_]+)\("(?P<code>[^"]+)",\s*"(?P<label>[^"]+)",\s*"(?P<msg>[^"]+)",\s*(?P<status>\d+)\)'
)
COMMENT_LINE = re.compile(r"^\s*//\s*(.+)$")


def parse_result_code(src_path: Path) -> list[dict]:
    """每条枚举可带行内 // 注释作为说明。返回有序列表。"""
    rows = []
    pending_comment: str | None = None
    for raw in src_path.read_text(encoding="utf-8").splitlines():
        if (m := COMMENT_LINE.match(raw)):
            pending_comment = m.group(1).strip()
            continue
        if (m := ENUM_LINE.match(raw)):
            rows.append(
                {
                    "name": m.group("name"),
                    "code": m.group("code"),
                    "label": m.group("label"),
                    "msg": m.group("msg"),
                    "status": int(m.group("status")),
                    "note": pending_comment,
                }
            )
            pending_comment = None
        elif raw.strip() and not raw.lstrip().startswith(("package", "import", "@", "}", "private", "public ")):
            # 任何非空非头部行打断 pending 注释
            pending_comment = None
    return rows


def render(rows: list[dict]) -> str:
    lines = [
        "# 错误码字典",
        "",
        "> **自动生成** — 由 `scripts/codegen/gen-error-codes-dict.py` 从 `batch-common/.../enums/ResultCode.java` 解析。**不要手动编辑此文件**；改错误码请改枚举源文件，重跑脚本。",
        "",
        f"共 {len(rows)} 条。HTTP 状态码遵循 RFC 7231。",
        "",
        "| code | HTTP | 中文 label | 默认 message | 触发说明 |",
        "|---|---|---|---|---|",
    ]
    for r in rows:
        note = r["note"] or "—"
        # markdown 表格 cell 内禁止裸竖线
        for k in ("label", "msg"):
            r[k] = r[k].replace("|", r"\|")
        note = note.replace("|", r"\|")
        lines.append(
            f'| `{r["code"]}` | {r["status"]} | {r["label"]} | {r["msg"]} | {note} |'
        )
    lines.extend(
        [
            "",
            "## 调用方建议处理",
            "",
            "| HTTP 段 | 客户端建议 |",
            "|---|---|",
            "| 2xx | 正常处理 |",
            "| 4xx (400/401/403/404/409/422/429) | 不要 retry，把 message 直接展示给用户或 PD |",
            "| 5xx (500/501/503) | 可短暂 retry（指数退避），仍失败上报告警 |",
            "",
            "特别注意：",
            "",
            "- `RATE_LIMITED` (429) → 退避后重试，不要无限刷",
            "- `SERVICE_UNAVAILABLE` (503) → 依赖组件抖动（如 Redis），稍后重试安全",
            "- `STATE_CONFLICT` (409) → 状态机已推进，**不要**重试，重新查询状态",
            "- `MISSING_IDEMPOTENCY_KEY` (400) → 写接口必须带 `Idempotency-Key` header",
            "",
            "## 相关",
            "",
            "- 源枚举：[`batch-common/.../enums/ResultCode.java`](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/ResultCode.java)",
            "- API 协议：[`../api/console-api-protocol.md`](../api/console-api-protocol.md) §错误码",
            "- 编码规约：[`../coding-conventions.md`](../coding-conventions.md) §5 异常体系",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="CI 模式：仅检查 dict 是否与源同步")
    args = parser.parse_args()

    if not SRC.exists():
        print(f"[error] 源文件不存在: {SRC}", file=sys.stderr)
        return 2

    rows = parse_result_code(SRC)
    if not rows:
        print(f"[error] 未解析到任何枚举条目，正则失配？", file=sys.stderr)
        return 2

    new_content = render(rows)

    if args.check:
        if not OUT.exists():
            print(f"[FAIL] dict 文件不存在: {OUT}", file=sys.stderr)
            print("       请运行: python3 scripts/codegen/gen-error-codes-dict.py", file=sys.stderr)
            return 1
        old_content = OUT.read_text(encoding="utf-8")
        if old_content != new_content:
            print(f"[FAIL] dict 与源不同步: {OUT}", file=sys.stderr)
            print("       请运行: python3 scripts/codegen/gen-error-codes-dict.py", file=sys.stderr)
            return 1
        print(f"[OK] dict 与源同步: {len(rows)} 条")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(new_content, encoding="utf-8")
    print(f"[OK] 写入 {OUT}: {len(rows)} 条错误码")
    return 0


if __name__ == "__main__":
    sys.exit(main())
