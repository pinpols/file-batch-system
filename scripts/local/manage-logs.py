#!/usr/bin/env python3
"""本地日志留存：默认预览，压缩校验后归档，显式清理过期归档包。"""

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_POLICY = ROOT / "config/local-log-retention.json"
MANIFEST = "log-retention-manifest.json"
FORMAT = "bfs-local-log-archive-v1"
CATEGORIES = ("runs", "app", "verification", "legacy")
LOG_REFERENCE = re.compile(r"(?<![\w/])logs/[^\s`\"'<>\[\]{}()|,;]+")


@dataclass(frozen=True)
class File:
    path: Path
    fingerprint: tuple

    @property
    def size(self):
        return self.fingerprint[2]


def fingerprint(path):
    value = path.lstat()
    return stat_fingerprint(value, path)


def stat_fingerprint(value, path):
    if not stat.S_ISREG(value.st_mode):
        raise ValueError(f"不是普通文件: {path}")
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_mode)


def ordinary_files(directory):
    """不跟随软链；未知文件类型使运行目录整体不参与归档。"""
    for parent, directories, names in os.walk(directory, followlinks=False):
        for name in directories + names:
            path = Path(parent) / name
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode) or not (stat.S_ISREG(mode) or stat.S_ISDIR(mode)):
                raise ValueError(f"目录包含软链或特殊文件: {path}")
        for name in names:
            path = Path(parent) / name
            yield File(path, fingerprint(path))


def safe_path(path, root):
    relative = path.relative_to(root)
    if not relative.parts or relative.parts[0] != "logs":
        raise ValueError(f"目标不在 logs 内: {path}")
    if path.resolve() != path:
        raise ValueError(f"目标包含软链: {path}")


def pinned(path, root):
    if path.name in {".keep", ".gitkeep"} or Path(str(path) + ".keep").exists():
        return True
    return any((parent / ".keep").exists() for parent in [path.parent, *path.parents]
               if parent == root / "logs" or parent.is_relative_to(root / "logs"))


def references(root):
    """文档中的具体日志路径保留；通用目录示例不冻结整类日志。"""
    result = set()
    documents = list((root / "docs").rglob("*.md")) + list(root.glob("*.md"))
    for document in documents:
        if document.is_symlink():
            continue
        for match in LOG_REFERENCE.findall(document.read_text(encoding="utf-8")):
            relative = match.rstrip(".:，。；")
            if not any(character in relative for character in "*?$"):
                path = root / relative
                if ".." not in Path(relative).parts:
                    result.add(path)
                    result.add(path.resolve())
    return result


def linked_targets(root):
    result = set()
    for parent, directories, files in os.walk(root / "logs", followlinks=False):
        for name in directories + files:
            path = Path(parent) / name
            if path.is_symlink():
                result.add(path.resolve())
    return result


def open_files(root):
    executable = shutil.which("lsof")
    if not executable:
        raise ValueError("需要 lsof 识别打开中的文件；macOS 自带，Linux 请安装 lsof")
    outcome = subprocess.run([executable, "-nP", "-Fn", "+D", str(root / "logs")],
                             text=True, capture_output=True, timeout=60, check=False)
    if outcome.returncode not in (0, 1) or outcome.stderr.strip():
        raise ValueError(f"无法完整检查日志占用: {outcome.stderr.strip()}")
    return {Path(line[1:]).resolve() for line in outcome.stdout.splitlines()
            if line.startswith("n/")}


def protected(file, root, refs, links, opened):
    path = file.path
    ancestors = [parent for parent in path.parents if parent.is_relative_to(root / "logs")]
    return (pinned(path, root) or path in opened
            or path in links or any(parent in links for parent in ancestors)
            or path in refs or any(parent in refs and len(parent.relative_to(root).parts) >= 4
                                   for parent in ancestors))


def source_units(root, category):
    logs = root / "logs"
    if category == "runs":
        for directory in sorted((logs / "runs").glob("*")):
            if directory.is_symlink() or not directory.is_dir():
                continue
            children = list(directory.iterdir())
            if any(child.is_file() and not child.is_symlink() for child in children):
                yield directory
            else:
                yield from (child for child in sorted(children)
                            if child.is_dir() and not child.is_symlink())
    elif category == "verification":
        for parent, directories, files in os.walk(logs / "verification", followlinks=False):
            directories[:] = [name for name in directories
                               if not (Path(parent) / name).is_symlink()]
            for name in sorted(files):
                yield Path(parent) / name
    elif category == "app":
        for directory in sorted((logs / "archive").glob("app*")):
            if directory.is_dir() and not directory.is_symlink() and (
                    directory.name == "app" or directory.name.startswith("app-")):
                yield from sorted(directory.iterdir())
    else:
        for directory in sorted((logs / "archive").glob("*")):
            if directory.name in {"legacy", "docker"} or directory.name.startswith("docker-stopped-"):
                yield from sorted(directory.iterdir())


def plan(root, policy, categories, now, refs, links, opened):
    selected, skipped, used = {}, {}, 0
    maximum = policy["max_source_mib"] * 1024 * 1024
    for category in categories:
        selected[category] = []
        skipped[category] = 0
        cutoff_ns = int((now - policy["keep_days"][category] * 86400) * 1e9)
        for unit in source_units(root, category):
            try:
                safe_path(unit, root)
                files = tuple(ordinary_files(unit)) if unit.is_dir() else (File(unit, fingerprint(unit)),)
                if (not files or any(file.fingerprint[3] >= cutoff_ns
                                     or protected(file, root, refs, links, opened) for file in files)
                        or used + sum(file.size for file in files) > maximum):
                    skipped[category] += 1
                    continue
                selected[category].append((unit, files))
                used += sum(file.size for file in files)
            except (ValueError, FileNotFoundError):
                skipped[category] += 1
    return selected, skipped


class HashReader:
    def __init__(self, stream):
        self.stream = stream
        self.digest = hashlib.sha256()

    def read(self, size):
        chunk = self.stream.read(size)
        self.digest.update(chunk)
        return chunk


def checksum(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def verify_archive(path):
    with tarfile.open(path, "r:gz") as archive:
        manifest = json.load(archive.extractfile(MANIFEST))
        if manifest["format"] != FORMAT:
            raise ValueError(f"未知归档格式: {path}")
        members = archive.getmembers()
        expected = {entry["path"]: entry for entry in manifest["files"]}
        if (len(expected) != len(manifest["files"]) or len(members) != len(expected) + 1
                or {member.name for member in members} != set(expected) | {MANIFEST}):
            raise ValueError(f"归档条目不匹配: {path}")
        for member in members:
            if member.name == MANIFEST:
                continue
            relative = Path(member.name)
            if (relative.is_absolute() or ".." in relative.parts or not member.isfile()
                    or not relative.parts or relative.parts[0] != "logs"):
                raise ValueError(f"不安全的归档条目: {member.name}")
            entry = expected[member.name]
            digest = hashlib.sha256()
            stream = archive.extractfile(member)
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
            if digest.hexdigest() != entry["sha256"] or member.size != entry["size"]:
                raise ValueError(f"归档校验失败: {member.name}")
        return manifest


def archive_units(root, category, units, now):
    directory = root / "logs/archive" / f"retention-{category}"
    safe_path(directory, root)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    size = sum(file.size for _, files in units for file in files)
    if shutil.disk_usage(directory).free < size + 1024 * 1024 + sum(len(files) for _, files in units) * 2048:
        raise ValueError("磁盘可用空间不足，保留原日志并停止归档")
    destination = directory / (time.strftime("%Y%m%d-%H%M%S", time.gmtime(now))
                               + "-" + uuid.uuid4().hex + ".tar.gz")
    descriptor, temporary = tempfile.mkstemp(prefix=".partial-", dir=directory)
    entries = []
    try:
        with os.fdopen(descriptor, "wb") as output:
            with tarfile.open(fileobj=output, mode="w:gz") as archive:
                for _, files in units:
                    for file in files:
                        safe_path(file.path, root)
                        if fingerprint(file.path) != file.fingerprint:
                            raise ValueError(f"归档时文件已变化: {file.path}")
                        with os.fdopen(os.open(file.path, os.O_RDONLY | os.O_NOFOLLOW), "rb") as stream:
                            if stat_fingerprint(os.fstat(stream.fileno()), file.path) != file.fingerprint:
                                raise ValueError(f"归档时文件已替换: {file.path}")
                            reader = HashReader(stream)
                            header = tarfile.TarInfo(str(file.path.relative_to(root)))
                            header.size = file.size
                            header.mode = stat.S_IMODE(file.fingerprint[4])
                            header.mtime = file.fingerprint[3] / 1e9
                            archive.addfile(header, reader)
                            if stat_fingerprint(os.fstat(stream.fileno()), file.path) != file.fingerprint:
                                raise ValueError(f"归档时文件仍在写入: {file.path}")
                        if fingerprint(file.path) != file.fingerprint:
                            raise ValueError(f"归档时文件已变化: {file.path}")
                        entries.append({"path": str(file.path.relative_to(root)), "size": file.size,
                                        "mtime_ns": file.fingerprint[3], "sha256": reader.digest.hexdigest()})
                manifest = {"format": FORMAT, "root": str(root), "category": category,
                            "created_at": now, "files": entries}
                payload = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
                header = tarfile.TarInfo(MANIFEST)
                header.size, header.mode = len(payload), 0o600
                archive.addfile(header, io.BytesIO(payload))
            output.flush()
            os.fsync(output.fileno())
        verify_archive(Path(temporary))
        os.rename(temporary, destination)
        directory_descriptor = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        if Path(temporary).exists():
            Path(temporary).unlink()
    return destination, {root / entry["path"]: entry["sha256"] for entry in entries}


def remove_archived_sources(root, units, hashes):
    refs, links, opened = references(root), linked_targets(root), open_files(root)
    for unit, files in units:
        if any(protected(file, root, refs, links, opened)
               or fingerprint(file.path) != file.fingerprint
               or checksum(file.path) != hashes[file.path] for file in files):
            print(f"保留已变化或重新受保护的原文件: {unit.relative_to(root)}")
            continue
        for file in files:
            safe_path(file.path, root)
            file.path.unlink()
        if unit.is_dir():
            for parent, _, _ in os.walk(unit, topdown=False):
                try:
                    Path(parent).rmdir()
                except OSError:
                    pass  # 新增文件或固定标记导致非空时保留目录。


def expired_archives(root, policy, now, refs, opened, categories=CATEGORIES):
    for category in categories:
        directory = root / "logs/archive" / f"retention-{category}"
        for path in sorted(directory.glob("*.tar.gz")):
            safe_path(path, root)
            if pinned(path, root) or path in refs or path in opened:
                continue
            manifest = verify_archive(path)
            if (manifest["root"] != str(root) or manifest["category"] != category
                    or now - manifest["created_at"] <= policy["archive_days"] * 86400
                    or any(root / entry["path"] in refs or pinned(root / entry["path"], root)
                           for entry in manifest["files"])):
                continue
            yield path


@contextmanager
def maintenance_lock(root):
    directory = root / ".local/run/locks"
    if directory.resolve() != directory:
        raise ValueError("锁目录包含软链，拒绝执行")
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "log-retention.lock").open("a") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
            raise ValueError("另一日志归档任务正在执行") from failure
        yield


def load_policy(path):
    policy = json.loads(path.read_text(encoding="utf-8"))
    values = [policy["archive_days"], policy["max_source_mib"],
              *(policy["keep_days"][category] for category in CATEGORIES)]
    if any(type(value) is not int or value <= 0 for value in values):
        raise ValueError("留存天数与单次大小必须为正整数")
    return policy


def execute(args, root, policy):
    now = time.time()
    refs, links, opened = references(root), linked_targets(root), open_files(root)
    categories = tuple(dict.fromkeys(args.category or CATEGORIES))
    selected, skipped = plan(root, policy, categories, now, refs, links, opened)
    print(f"{'执行' if args.apply else '预览'}: {root}")
    for category, units in selected.items():
        size = sum(file.size for _, files in units for file in files)
        print(f"{category}: 归档候选 {len(units)} 项 / {size / 1024 / 1024:.2f} MiB，"
              f"保留或超过单次上限 {skipped[category]} 项（原文保留 {policy['keep_days'][category]} 天）")
        for unit, _ in units[:8]:
            print(f"  {unit.relative_to(root)}")
        if args.apply and units:
            destination, hashes = archive_units(root, category, units, now)
            print(f"校验通过: {destination.relative_to(root)}")
            remove_archived_sources(root, units, hashes)
    if args.prune_archives:
        for path in expired_archives(root, policy, now, references(root), open_files(root), categories):
            print(f"过期归档包: {path.relative_to(root)}")
            if args.apply:
                path.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--category", action="append", choices=CATEGORIES)
    parser.add_argument("--keep-days", type=int, help="覆盖所选类别的原文保留天数")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-root", type=Path, help="执行时必须传目标工作树绝对路径")
    parser.add_argument("--prune-archives", action="store_true", help="同时列出/删除超过归档保留期的受管包")
    args = parser.parse_args()
    root = args.root.resolve()
    if not (root / ".git").exists() or not (root / "logs").is_dir() or (root / "logs").is_symlink():
        parser.error("目标必须是包含真实 logs 目录的 Git 工作树")
    if args.apply and (not args.confirm_root or not args.confirm_root.is_absolute()
                       or args.confirm_root.resolve() != root):
        parser.error("--apply 需要 --confirm-root <目标工作树绝对路径>")
    try:
        policy = load_policy(args.policy)
        if args.keep_days is not None:
            if args.keep_days <= 0:
                raise ValueError("--keep-days 必须为正整数")
            for category in args.category or CATEGORIES:
                policy["keep_days"][category] = args.keep_days
        if args.apply:
            with maintenance_lock(root):
                execute(args, root, policy)
        else:
            execute(args, root, policy)
    except (OSError, ValueError, KeyError, tarfile.TarError, subprocess.TimeoutExpired) as failure:
        print(f"日志归档失败，未完成部分的原日志予以保留: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
