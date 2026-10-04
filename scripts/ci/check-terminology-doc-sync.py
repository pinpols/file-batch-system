#!/usr/bin/env python3
"""校验核心术语文档中的枚举值与 Java 事实源一致。"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ENUM_ROOT = ROOT / "batch-common/src/main/java/io/github/pinpols/batch/common/enums"
ENUM_SOURCES = {
    name: ENUM_ROOT / f"{name}.java"
    for name in (
        "JobInstanceStatus",
        "JobType",
        "CatchUpPolicyType",
        "CompensationCommandStatus",
        "FileChannelAuthType",
        "FileChannelType",
        "FileStatus",
        "OutboxPublishStatus",
        "PartitionStatus",
        "PipelineRunStatus",
        "RetryPolicyType",
        "RetryScheduleStatus",
        "RunMode",
        "ScheduleType",
        "StepInstanceStatus",
        "TaskStatus",
        "TriggerType",
        "TriggerRequestStatus",
        "WorkflowEdgeType",
        "WorkflowJoinMode",
        "WorkflowNodeRunStatus",
        "WorkflowNodeType",
        "WorkflowRunStatus",
        "WorkflowType",
        "WorkerRegistryStatus",
    )
}
DOCUMENTS = (
    ROOT / "docs/dict/glossary.md",
    ROOT / "docs/architecture/core-model.md",
    ROOT / "docs/design/status-state-machines.md",
    ROOT / "docs/architecture/pipeline-vs-workflow-boundary.md",
    ROOT / "docs/coding-conventions.md",
)
REQUIRED_MARKERS = {
    "docs/dict/glossary.md": {
        "ScheduleType",
        "TriggerType",
        "WorkflowEdgeType",
        "WorkflowJoinMode",
        "WorkflowNodeType",
        "RunMode",
    },
    "docs/architecture/core-model.md": {
        "JobInstanceStatus",
        "WorkflowRunStatus",
        "WorkflowNodeRunStatus",
        "PartitionStatus",
        "StepInstanceStatus",
        "TaskStatus",
    },
    "docs/design/status-state-machines.md": {
        "JobInstanceStatus",
        "PipelineRunStatus",
        "WorkflowRunStatus",
        "WorkflowNodeRunStatus",
        "TaskStatus",
        "PartitionStatus",
        "StepInstanceStatus",
        "OutboxPublishStatus",
        "RetryScheduleStatus",
        "TriggerRequestStatus",
        "CompensationCommandStatus",
        "WorkerRegistryStatus",
    },
    "docs/architecture/pipeline-vs-workflow-boundary.md": {
        "PipelineRunStatus",
        "WorkflowEdgeType",
        "WorkflowJoinMode",
        "WorkflowNodeType",
        "WorkflowRunStatus",
    },
    "docs/coding-conventions.md": {
        "JobType",
        "ScheduleType",
        "RetryPolicyType",
        "CatchUpPolicyType",
        "WorkflowType",
        "WorkflowNodeType",
        "WorkflowEdgeType",
        "FileChannelType",
        "FileChannelAuthType",
        "OutboxPublishStatus",
        "TriggerType",
        "TriggerRequestStatus",
        "JobInstanceStatus",
        "WorkflowRunStatus",
        "FileStatus",
    },
}
MARKER = re.compile(
    r"<!-- enum-sync:(?P<name>[A-Za-z][A-Za-z0-9]*):start -->"
    r"(?P<body>.*?)"
    r"<!-- enum-sync:(?P=name):end -->",
    re.DOTALL,
)
ENUM_CONSTANT = re.compile(r"^\s{2}([A-Z][A-Z0-9_]*)\s*\(", re.MULTILINE)
GATE_CODE = "TERMINOLOGY_DOC_SYNC"
GATE_NAME = "核心术语文档同步"


def enum_values(name: str) -> list[str]:
    source = ENUM_SOURCES[name]
    text = source.read_text(encoding="utf-8")
    declaration = re.search(rf"\benum\s+{re.escape(name)}\b[^{{]*\{{", text)
    if declaration is None:
        raise ValueError(f"enum declaration not found: {source.relative_to(ROOT)}")
    enum_body = text[declaration.end() :]
    first_field = re.search(r"^\s*private\s+", enum_body, re.MULTILINE)
    constants = enum_body[: first_field.start()] if first_field else enum_body
    values = ENUM_CONSTANT.findall(constants)
    if not values:
        raise ValueError(f"enum constants not found: {source.relative_to(ROOT)}")
    return values


def rendered_values(name: str) -> str:
    return ", ".join(f"`{value}`" for value in enum_values(name))


def synchronized_body(original: str, values: str) -> str:
    if original.startswith("\n") and original.endswith("\n"):
        return f"\n{values}\n"
    return values


def process_document(path: Path, write: bool) -> list[str]:
    relative = path.relative_to(ROOT).as_posix()
    text = path.read_text(encoding="utf-8")
    found = Counter(match.group("name") for match in MARKER.finditer(text))
    errors: list[str] = []

    for name in sorted(REQUIRED_MARKERS[relative]):
        count = found[name]
        if count != 1:
            errors.append(f"{relative}: enum-sync:{name} marker count is {count}, expected 1")

    unknown = sorted(set(found) - set(ENUM_SOURCES))
    for name in unknown:
        errors.append(f"{relative}: unknown enum-sync source: {name}")

    def replace(match: re.Match[str]) -> str:
        name = match.group("name")
        if name not in ENUM_SOURCES:
            return match.group(0)
        values = rendered_values(name)
        actual = match.group("body").strip()
        if actual != values and not write:
            errors.append(
                f"{relative}: {name} is stale; expected {values}, found {actual or '<empty>'}"
            )
            return match.group(0)
        body = synchronized_body(match.group("body"), values)
        return (
            f"<!-- enum-sync:{name}:start -->"
            f"{body}"
            f"<!-- enum-sync:{name}:end -->"
        )

    updated = MARKER.sub(replace, text)
    if write and updated != text:
        path.write_text(updated, encoding="utf-8")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--write",
        action="store_true",
        help="使用 Java enum 刷新文档中的 enum-sync 块",
    )
    args = parser.parse_args()

    errors: list[str] = []
    try:
        for document in DOCUMENTS:
            errors.extend(process_document(document, args.write))
    except (OSError, ValueError) as exc:
        errors.append(str(exc))

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    action = "updated" if args.write else "verified"
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | action={action}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
