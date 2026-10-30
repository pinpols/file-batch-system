#!/usr/bin/env python3
# 用途：为真实 Spark E2E 准备受校验缓存，并向 Maven 传入隔离运行时。
# 用法：python3 scripts/local/spark-e2e-runtime.py -- <Maven 命令及参数...>；--check 只核实缓存，--prepare 显式准备运行时。
"""为真实 Spark E2E 准备受校验缓存，并向 Maven 传入隔离运行时。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request


SPARK_VERSION = "4.0.1"
ARCHIVE_SHA512 = (
    "ed81e63979c504178906bec0efff3f054e40e222b9353f57b77299bea1f661007196"
    "39d20e7ad9bb1642195b629c4eafa6058f2e175d1071b395ea3a91c53d55"
)
ARCHIVE_URL = (
    "https://files.pythonhosted.org/packages/ae/40/"
    "1414582f16c1d7b051c668c2e19c62d21a18bd181d944cb24f5ddbb2423f/"
    "pyspark-4.0.1.tar.gz"
)
ROOT = Path(__file__).resolve().parents[2]
RECOVERY_CLASS = "WorkerReportLostAfterBusinessCommitE2eIT"
SPARK_METHODS = {
    "shouldStopAtomicSparkReplay_whenWorkerCrashesAfterSparkOutputCommit",
    "shouldStopAtomicSparkReplay_whenWorkerJvmCrashesDuringActiveSparkAction",
}


def digest(path, algorithm="sha256"):
    checksum = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def needs_spark(command):
    """仅明确排除 Spark 的定向选择免去运行时前置，通配符保守处理。"""
    selectors = [arg[7:] for arg in command if arg.startswith("-Dtest=")]
    if not selectors or any(char in selectors[-1] for char in "*?%![]"):
        return True
    for selector in selectors[-1].split(","):
        name, _, methods = selector.strip().partition("#")
        name = name.removesuffix(".java").removesuffix(".class").replace("/", ".")
        if name.rsplit(".", 1)[-1] == RECOVERY_CLASS:
            if not methods or SPARK_METHODS.intersection(methods.split("+")):
                return True
    return False


def isolated_environment(spark_home):
    environment = os.environ.copy()
    environment.pop("PYTHONHOME", None)
    environment["SPARK_HOME"] = str(spark_home)
    environment["PYTHONNOUSERSITE"] = "1"
    environment["PYTHONPATH"] = os.pathsep.join(
        str(spark_home / "python/lib" / name)
        for name in ("pyspark.zip", "py4j-0.10.9.9-src.zip")
    )
    return environment


def validate(runtime):
    manifest_path = runtime / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("Spark 缓存缺失；先执行 python3 scripts/local/spark-e2e-runtime.py --prepare")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("archiveSha512") != ARCHIVE_SHA512:
        raise ValueError("Spark 缓存来源不匹配，使用 --prepare 在新的缓存目录重新准备")
    for relative, expected in manifest["files"].items():
        path = runtime / relative
        if not path.is_file() or digest(path) != expected:
            raise ValueError(f"Spark 缓存文件缺失或被修改: {relative}")
    spark_home = runtime / "spark"
    python = runtime / "python/bin/python"
    probe = (
        "import sys, pyspark, py4j; "
        "assert sys.version_info[:2] == (3, 12); "
        "assert pyspark.__version__ == '4.0.1'; "
        "assert py4j.__version__ == '0.10.9.9'"
    )
    subprocess.run([str(python), "-c", probe], env=isolated_environment(spark_home), check=True)
    return spark_home, python


def prepare(runtime, archive):
    if runtime.exists():
        validate(runtime)
        return
    python_name = os.environ.get("BATCH_E2E_SPARK_PYTHON", "python3.12")
    python = shutil.which(python_name)
    if not python:
        raise ValueError("需要 Python 3.12；设置 BATCH_E2E_SPARK_PYTHON 指向其可执行文件")
    subprocess.run(
        [python, "-I", "-c", "import sys; assert sys.version_info[:2] == (3, 12)"],
        check=True,
    )
    runtime.parent.mkdir(parents=True, exist_ok=True)
    lock = runtime.parent / "prepare.lock"
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise ValueError(f"另一个准备过程占用 {lock}；确认其退出后再重试") from error
    try:
        with tempfile.TemporaryDirectory(prefix="spark-prepare-", dir=runtime.parent) as temporary:
            staging = Path(temporary)
            source = Path(archive).expanduser().resolve() if archive else staging / "spark.tar.gz"
            if not archive:
                print(f"下载 Apache Spark {SPARK_VERSION} 并校验固定 SHA-512", file=sys.stderr)
                with urllib.request.urlopen(ARCHIVE_URL, timeout=120) as response:
                    with source.open("wb") as output:
                        shutil.copyfileobj(response, output)
            if digest(source, "sha512") != ARCHIVE_SHA512:
                raise ValueError("Spark archive SHA-512 不匹配，拒绝解包")
            installation = staging / "runtime"
            spark_home = installation / "spark"
            # 使用发行包自带的 jars 与两个 Python zip，无需 pip 或全局 site-packages。
            with tarfile.open(source, "r:gz") as distribution:
                for member in distribution:
                    parts = Path(member.name).parts
                    if len(parts) < 3 or parts[0] != f"pyspark-{SPARK_VERSION}":
                        continue
                    if parts[1] not in ("deps", "lib") or not member.isfile():
                        continue
                    if ".." in parts or Path(member.name).is_absolute():
                        raise ValueError("Spark archive 包含非法路径")
                    relative = Path(*parts[2:])
                    destination = spark_home / relative
                    if parts[1] == "lib":
                        destination = spark_home / "python/lib" / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with distribution.extractfile(member) as content:
                        with destination.open("wb") as output:
                            shutil.copyfileobj(content, output)
                    destination.chmod(member.mode & 0o777)
            subprocess.run(
                [python, "-I", "-m", "venv", "--without-pip", str(installation / "python")],
                check=True,
            )
            manifest = {
                "sparkVersion": SPARK_VERSION,
                "archiveSha512": ARCHIVE_SHA512,
                "source": ARCHIVE_URL,
                "files": {
                    str(path.relative_to(installation)): digest(path)
                    for path in sorted(spark_home.rglob("*")) if path.is_file()
                },
            }
            (installation / "manifest.json").write_text(
                json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
            )
            validate(installation)
            installation.rename(runtime)
    finally:
        lock.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true", help="显式下载/解包并校验 Spark 缓存")
    parser.add_argument("--archive", help="使用现有官方 pyspark-4.0.1.tar.gz 离线准备")
    parser.add_argument("--check", action="store_true", help="只核实缓存和 Python，不启动 Spark")
    parser.add_argument("command", nargs=argparse.REMAINDER, help="-- 后为待执行的 Maven 命令")
    arguments = parser.parse_args()
    command = arguments.command
    if command[:1] == ["--"]:
        command = command[1:]
    if arguments.archive and not arguments.prepare:
        parser.error("--archive 必须与 --prepare 一起使用")
    if command and not needs_spark(command):
        return 0 if arguments.check else subprocess.call(command)
    cache = Path(os.environ.get("BATCH_E2E_SPARK_CACHE", ROOT / "tmp/cache/spark-e2e"))
    runtime = cache.expanduser().resolve() / "spark-4.0.1-py312"
    if arguments.prepare:
        prepare(runtime, arguments.archive)
    spark_home, python = validate(runtime)
    if not command or arguments.check:
        print(f"Spark {SPARK_VERSION} / Python 3.12 缓存校验通过: {runtime}")
        return 0
    # 参数使用 argv 传递；不生成待 eval 的 shell 文本，也不隐式下载依赖。
    command += [f"-Dr25.spark.home={spark_home}", f"-Dr25.spark.python={python}"]
    return subprocess.call(command, env=isolated_environment(spark_home))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Spark E2E 运行时检查失败: {error}", file=sys.stderr)
        raise SystemExit(2) from error
