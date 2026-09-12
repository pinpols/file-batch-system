#!/usr/bin/env bash
# =========================================================
# build-apps.sh - 本地联调应用模块单独构建入口
# 说明：
# 1) 仅打包 8 个 Java 应用模块，不启动 Docker、不启动本地进程。
# 2) 默认执行低开销 Maven package，跳过测试、IT、PMD、Spotless 和发布类插件，
#    供 start-all.sh / 手工联调复用。
# 3) 默认增量构建（不 clean），Maven 自身会基于 mtime 决定是否重编；
#    若出现「类文件在偏移 0 处截断」、repackage 失败、或 *-exec.jar 体积极小，
#    多为 target/ 写入不完整（中断构建、磁盘或并行竞态），请用 CLEAN=1 强制清理后重编。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
mkdir -p "$RUNTIME_JAR_DIR"

# shellcheck source=maven-env.sh
source "$ROOT/scripts/local/maven-env.sh"
MVN="$(batch_resolve_maven_command "$ROOT")"

# CLEAN=1 强制清理；否则增量构建（实测未改动场景 40s → 9s）
if [[ "${CLEAN:-0}" == "1" ]]; then
  _CLEAN_GOAL="clean"
    echo "==> Maven 打包应用模块（clean package -Dmaven.test.skip=true，CLEAN=1）..."
else
  _CLEAN_GOAL=""
    echo "==> Maven 打包应用模块（增量 package -Dmaven.test.skip=true；强制清理用 CLEAN=1）..."
fi

# 共享测试基础设施已独立为 batch-test-support 主 artifact，生产打包无需编译任何测试源码。
# -T 2C：M 系列多核机器加倍 thread/core，实测 -16%
# -Dflatten.skip=true：local 不 install/deploy，跳过 flatten 插件
MODULES=(batch-orchestrator batch-trigger batch-console-api batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch batch-worker-atomic)
NAMES=(orchestrator trigger console worker-import worker-export worker-process worker-dispatch worker-atomic)
# DIRS：模块目录路径(与 MODULES 同序)。worker 模块迁到 batch-worker/ 下后,目录路径 ≠ artifactId,
# 故路径用 DIRS、jar 名仍用 MODULES(${module}-*-exec.jar 取的是 artifactId 不变)。
DIRS=(batch-orchestrator batch-trigger batch-console-api batch-worker/import batch-worker/export batch-worker/process batch-worker/dispatch batch-worker/atomic)

for i in "${!MODULES[@]}"; do
  find "$ROOT/${DIRS[$i]}/target" -maxdepth 1 -name "${MODULES[$i]}-*-exec.jar" -delete 2>/dev/null || true
done

"$MVN" -q -ntp -Dmaven.test.skip=true \
  -DskipITs=true \
  -Dspotless.check.skip=true \
  -Dpmd.skip=true \
  -Dcyclonedx.skip=true \
  -Dlicense.skip=true \
  -Dmaven.javadoc.skip=true \
  -Dflatten.skip=true \
  -pl batch-trigger,batch-orchestrator,batch-worker/import,batch-worker/export,batch-worker/process,batch-worker/dispatch,batch-worker/atomic,batch-console-api \
  -am ${_CLEAN_GOAL} package -T 2C

echo "==> 复制可执行 jar 到 build/runtime-jars/..."
for i in "${!MODULES[@]}"; do
  module="${MODULES[$i]}"
  name="${NAMES[$i]}"
  dir="${DIRS[$i]}"
  jar="$(find "$ROOT/$dir/target" -maxdepth 1 -name "${module}-*-exec.jar" 2>/dev/null \
          | grep -Ev 'sources|javadoc' | head -1 || true)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "ERROR: 未找到可执行 exec jar: $dir/target/${module}-*-exec.jar" >&2
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
