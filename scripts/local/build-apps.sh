#!/usr/bin/env bash
# =========================================================
# build-apps.sh - 本地联调应用模块单独构建入口
# Notes:
# 1) 仅打包六个 Java 应用模块，不启动 Docker、不启动本地进程。
# 2) 默认执行 Maven package -DskipTests，供 start-all.sh / 手工联调复用。
# 3) 默认增量构建（不 clean），Maven 自身会基于 mtime 决定是否重编；
#    若出现「类文件在偏移 0 处截断」、repackage 失败、或 *-exec.jar 体积极小，
#    多为 target/ 写入不完整（中断构建、磁盘或并行竞态），请用 CLEAN=1 强制清理后重编。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
mkdir -p "$RUNTIME_JAR_DIR"

# 优先使用 mvnd（Maven Daemon），没装则降级到 mvn
_MVND_BIN="${HOME}/.local/bin/mvnd"
if [[ -x "$_MVND_BIN" ]]; then
  export MVND_HOME="${HOME}/.local/share/maven-mvnd-1.0.5-darwin-aarch64"
  MVN="$_MVND_BIN"
else
  MVN=$(command -v mvnd 2>/dev/null || command -v mvn)
fi

# CLEAN=1 强制清理；否则增量构建（实测未改动场景 40s → 9s）
if [[ "${CLEAN:-0}" == "1" ]]; then
  _CLEAN_GOAL="clean"
  echo "==> Maven 打包应用模块（clean package -DskipTests，CLEAN=1）..."
else
  _CLEAN_GOAL=""
  echo "==> Maven 打包应用模块（增量 package -DskipTests；强制清理用 CLEAN=1）..."
fi

# 用 -DskipTests 而非 -Dmaven.test.skip=true：
# 前者只跳过测试执行，保留 test-classes 和 test-jar 构建；
# 后者会跳过 test-jar 生成，导致 worker-core/console-api/trigger/orchestrator
# 对 batch-common:tests 的依赖解析失败。
# -T 2C：M 系列多核机器加倍 thread/core，实测 -16%
# -Dflatten.skip=true：local 不 install/deploy，跳过 flatten 插件
"$MVN" -q -DskipTests \
  -Dcyclonedx.skip=true \
  -Dlicense.skip=true \
  -Dmaven.javadoc.skip=true \
  -Dflatten.skip=true \
  -pl batch-trigger,batch-orchestrator,batch-worker-import,batch-worker-export,batch-worker-process,batch-worker-dispatch,batch-console-api \
  -am ${_CLEAN_GOAL} package -T 2C

echo "==> 复制可执行 jar 到 build/runtime-jars/..."
MODULES=(batch-orchestrator batch-trigger batch-console-api batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch)
NAMES=(orchestrator trigger console worker-import worker-export worker-process worker-dispatch)
for i in "${!MODULES[@]}"; do
  module="${MODULES[$i]}"
  name="${NAMES[$i]}"
  jar="$(ls "$ROOT/$module/target/${module}"-*-exec.jar 2>/dev/null | grep -Ev 'sources|javadoc' | head -1 || true)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    jar="$(ls "$ROOT/$module/target/${module}"-*.jar 2>/dev/null | grep -Ev 'sources|javadoc|\.original$|-exec\.jar$' | head -1 || true)"
  fi
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "ERROR: 未找到可执行 jar: $module/target/${module}-*.jar" >&2
    exit 1
  fi
  _bytes="$(wc -c <"$jar" | awk '{print $1}')"
  if [[ "${_bytes:-0}" -lt 4096 ]]; then
    echo "ERROR: $jar 仅 ${_bytes} 字节，疑似损坏（正常 exec jar 至少数 MB）。请执行: CLEAN=1 bash scripts/local/build-apps.sh" >&2
    exit 1
  fi
  cp -f "$jar" "$RUNTIME_JAR_DIR/${name}.jar"
  echo "  ${name}.jar <- $(basename "$jar")"
done

echo "==> 构建完成（jar 已输出到 build/runtime-jars/）"
