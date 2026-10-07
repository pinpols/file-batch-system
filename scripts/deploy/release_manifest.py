#!/usr/bin/env python3
"""校验发布清单、生成 Compose 覆盖层并提取后端镜像 digest。"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


BACKEND_SERVICES = (
    "console-api",
    "trigger",
    "orchestrator",
    "worker-import",
    "worker-export",
    "worker-process",
    "worker-dispatch",
    "worker-atomic",
)
ALL_SERVICES = ("frontend", *BACKEND_SERVICES)
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
IMAGE_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}$")
RELEASE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
ROOT_FIELDS = {
    "schemaVersion",
    "releaseId",
    "createdAt",
    "previousStableReleaseId",
    "commits",
    "images",
    "verification",
}
VERIFICATION_FIELDS = {"status", "verifiedAt", "workflowRunUrl"}


class ManifestError(ValueError):
    """发布清单不满足不可变部署契约。"""


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ManifestError(f"无法读取 JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ManifestError(f"JSON 顶层必须是对象: {path}")
    return value


def validate_manifest(manifest: dict[str, Any]) -> None:
    errors: list[str] = []
    extra_root_fields = sorted(set(manifest) - ROOT_FIELDS)
    if extra_root_fields:
        errors.append(f"包含未知顶层字段: {', '.join(extra_root_fields)}")
    if manifest.get("schemaVersion") != 1:
        errors.append("schemaVersion 必须为 1")
    release_id = manifest.get("releaseId")
    if not isinstance(release_id, str) or not RELEASE_ID_PATTERN.fullmatch(release_id):
        errors.append("releaseId 只能包含字母、数字、点、下划线和连字符")
    created_at = manifest.get("createdAt")
    if not isinstance(created_at, str) or not _is_datetime(created_at):
        errors.append("createdAt 必须是 ISO-8601 时间")
    previous_release_id = manifest.get("previousStableReleaseId")
    if previous_release_id is not None and (
        not isinstance(previous_release_id, str)
        or not RELEASE_ID_PATTERN.fullmatch(previous_release_id)
    ):
        errors.append("previousStableReleaseId 必须为空或合法 releaseId")

    commits = manifest.get("commits")
    if not isinstance(commits, dict):
        errors.append("commits 必须是对象")
    else:
        extra_commits = sorted(set(commits) - {"backend", "frontend"})
        if extra_commits:
            errors.append(f"commits 包含未知字段: {', '.join(extra_commits)}")
        for component in ("backend", "frontend"):
            value = commits.get(component)
            if not isinstance(value, str) or not SHA_PATTERN.fullmatch(value):
                errors.append(f"commits.{component} 必须是 40 位小写 Git SHA")

    images = manifest.get("images")
    if not isinstance(images, dict):
        errors.append("images 必须是对象")
    else:
        missing = [service for service in ALL_SERVICES if service not in images]
        extra = sorted(set(images) - set(ALL_SERVICES))
        if missing:
            errors.append(f"images 缺少服务: {', '.join(missing)}")
        if extra:
            errors.append(f"images 包含未知服务: {', '.join(extra)}")
        for service in ALL_SERVICES:
            image = images.get(service)
            if not isinstance(image, str) or not IMAGE_PATTERN.fullmatch(image):
                errors.append(f"images.{service} 必须使用 registry/repository@sha256:<64 hex>")

    verification = manifest.get("verification", {})
    if not isinstance(verification, dict):
        errors.append("verification 必须是对象")
    else:
        for environment, value in verification.items():
            if environment not in {"staging", "production"}:
                errors.append(f"verification 包含未知环境: {environment}")
                continue
            if not isinstance(value, dict):
                errors.append(f"verification.{environment} 必须是对象")
                continue
            extra_verification_fields = sorted(set(value) - VERIFICATION_FIELDS)
            if extra_verification_fields:
                errors.append(
                    f"verification.{environment} 包含未知字段: "
                    + ", ".join(extra_verification_fields)
                )
            if value.get("status") not in {"PENDING", "PASSED", "FAILED"}:
                errors.append(f"verification.{environment}.status 非法")
            verified_at = value.get("verifiedAt")
            if verified_at is not None and (
                not isinstance(verified_at, str) or not _is_datetime(verified_at)
            ):
                errors.append(f"verification.{environment}.verifiedAt 非法")
            workflow_run_url = value.get("workflowRunUrl")
            if workflow_run_url is not None and (
                not isinstance(workflow_run_url, str) or not _is_uri(workflow_run_url)
            ):
                errors.append(f"verification.{environment}.workflowRunUrl 非法")

    if errors:
        raise ManifestError("; ".join(errors))


def _is_datetime(value: str) -> bool:
    if "T" not in value:
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def _is_uri(value: str) -> bool:
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def render_compose(manifest: dict[str, Any]) -> str:
    validate_manifest(manifest)
    images = manifest["images"]
    lines = ["# 由 release_manifest.py 生成；不要手工编辑。", "services:"]
    for service in BACKEND_SERVICES:
        lines.extend(
            (
                f"  {service}:",
                f"    image: {images[service]}",
                "    pull_policy: always",
            )
        )
    return "\n".join(lines) + "\n"


def backend_fragment(
    metadata: dict[str, Any], bake_plan: dict[str, Any], git_sha: str
) -> dict[str, Any]:
    if not SHA_PATTERN.fullmatch(git_sha):
        raise ManifestError("git-sha 必须是 40 位小写 Git SHA")
    images: dict[str, str] = {}
    targets = bake_plan.get("target")
    if not isinstance(targets, dict):
        raise ManifestError("Bake plan 缺少 target")
    for service in BACKEND_SERVICES:
        target = metadata.get(service)
        if not isinstance(target, dict):
            raise ManifestError(f"Bake metadata 缺少 target: {service}")
        digest = target.get("containerimage.digest")
        if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ManifestError(f"Bake metadata 缺少有效 digest: {service}")
        plan_target = targets.get(service)
        tags = plan_target.get("tags") if isinstance(plan_target, dict) else None
        if not isinstance(tags, list) or len(tags) != 1 or not isinstance(tags[0], str):
            raise ManifestError(f"Bake plan 必须为 target {service} 提供唯一镜像 tag")
        repository = _repository_from_image_name(tags[0])
        if not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*", repository):
            raise ManifestError(f"Bake metadata 镜像仓库非法: {service}")
        images[service] = f"{repository}@{digest}"
    return {
        "schemaVersion": 1,
        "kind": "BackendImageSet",
        "gitSha": git_sha,
        "images": images,
    }


def _repository_from_image_name(image_name: str) -> str:
    without_digest = image_name.split("@", 1)[0]
    last_slash = without_digest.rfind("/")
    last_colon = without_digest.rfind(":")
    return without_digest[:last_colon] if last_colon > last_slash else without_digest


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="校验完整 release manifest")
    validate.add_argument("manifest", type=Path)

    render = subparsers.add_parser("render-compose", help="生成后端 Compose digest 覆盖层")
    render.add_argument("manifest", type=Path)
    render.add_argument("--output", type=Path)

    fragment = subparsers.add_parser("backend-fragment", help="从 Bake metadata 提取后端 digest")
    fragment.add_argument("--metadata", required=True, type=Path)
    fragment.add_argument("--plan", required=True, type=Path)
    fragment.add_argument("--git-sha", required=True)
    fragment.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "validate":
            validate_manifest(read_json(args.manifest))
            print(f"release manifest valid: {args.manifest}")
        elif args.command == "render-compose":
            rendered = render_compose(read_json(args.manifest))
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(rendered, encoding="utf-8")
            else:
                sys.stdout.write(rendered)
        elif args.command == "backend-fragment":
            write_json(
                args.output,
                backend_fragment(
                    read_json(args.metadata), read_json(args.plan), args.git_sha
                ),
            )
    except ManifestError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
