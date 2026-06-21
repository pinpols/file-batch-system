#!/usr/bin/env bash
# 防漂移守卫:helm chart 里的告警规则副本必须与 canonical 字节一致。
#
# 背景:k8s 部署经 templates/prometheusrule.yaml 下发告警规则,内容来自 chart-local
# files/prometheus-batch-rules.yml。该副本与 docker-compose 路径用的 canonical
# docker/observability/prometheus-batch-rules.yml 是同一份规则的两个落地点。
# 改了 canonical 忘了同步 chart 副本 → k8s 与 docker 告警漂移(最坏:新加的关键告警
# 在生产 k8s 根本没下发)。本守卫让这种漂移在 CI 直接红。
#
# 用法:scripts/ci/check-helm-prometheusrule-sync.sh
#   一致 → exit 0;漂移 → 打印 diff + exit 1。
# 同步办法:cp docker/observability/prometheus-batch-rules.yml \
#            helm/batch-platform/files/prometheus-batch-rules.yml
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
canonical="$repo_root/docker/observability/prometheus-batch-rules.yml"
chart_copy="$repo_root/helm/batch-platform/files/prometheus-batch-rules.yml"

for f in "$canonical" "$chart_copy"; do
  if [[ ! -f "$f" ]]; then
    echo "FAIL: 缺文件 $f" >&2
    exit 1
  fi
done

if cmp -s "$canonical" "$chart_copy"; then
  echo "OK: helm 告警规则副本与 canonical 一致"
  exit 0
fi

echo "FAIL: helm chart 告警规则副本与 canonical 漂移:" >&2
diff -u "$canonical" "$chart_copy" >&2 || true
echo >&2
echo "修复:cp docker/observability/prometheus-batch-rules.yml helm/batch-platform/files/prometheus-batch-rules.yml" >&2
exit 1
