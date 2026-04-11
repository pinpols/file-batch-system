#!/usr/bin/env bash
# =========================================================
# build-apps.sh - 本地联调应用模块单独构建入口
# Notes:
# 1) 仅打包六个 Java 应用模块，不启动 Docker、不启动本地进程。
# 2) 默认执行 Maven package -DskipTests，供 start-all.sh / 手工联调复用。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
mkdir -p "$RUNTIME_JAR_DIR"

echo "==> Maven 打包应用模块（clean package -Dmaven.test.skip=true）..."
# 优先使用 mvnd（Maven Daemon），没装则降级到 mvn
_MVND_BIN="${HOME}/.local/bin/mvnd"
if [[ -x "$_MVND_BIN" ]]; then
  export MVND_HOME="${HOME}/.local/share/maven-mvnd-1.0.5-darwin-aarch64"
  MVN="$_MVND_BIN"
else
  MVN=$(command -v mvnd 2>/dev/null || command -v mvn)
fi
"$MVN" -q -Dmaven.test.skip=true \
  -Dcyclonedx.skip=true \
  -Dlicense.skip=true \
  -Dmaven.javadoc.skip=true \
  -pl batch-trigger,batch-orchestrator,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
  -am clean package -T 1C

echo "==> 复制可执行 jar 到 build/runtime-jars/..."
MODULES=(batch-orchestrator batch-trigger batch-console-api batch-worker-import batch-worker-export batch-worker-dispatch)
NAMES=(orchestrator trigger console worker-import worker-export worker-dispatch)
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
  cp -f "$jar" "$RUNTIME_JAR_DIR/${name}.jar"
  echo "  ${name}.jar <- $(basename "$jar")"
done

echo "==> 构建完成（jar 已输出到 build/runtime-jars/）"
