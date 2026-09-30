#!/usr/bin/env bash
set -euo pipefail

# 该脚本只验收 staging 的滚动升级结果，不自动执行 drain、删除 Pod 或回滚。
# 发布动作必须由发布系统或运维人员按 rolling-upgrade-workers.md 执行。

NAMESPACE="${NAMESPACE:?必须设置 staging Namespace}"
WORKER_DEPLOYMENTS="${WORKER_DEPLOYMENTS:?必须设置待验收的 Worker Deployment 列表，逗号分隔}"
KUBE_CONTEXT="${KUBE_CONTEXT:-}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-15m}"
EVIDENCE_DIR="${EVIDENCE_DIR:-artifacts/staging/worker-rollout-$(date -u +%Y%m%dT%H%M%SZ)}"

command -v kubectl >/dev/null 2>&1 || {
  printf '缺少命令: kubectl\n' >&2
  exit 2
}
mkdir -p "${EVIDENCE_DIR}"

kubectl_args=()
if [[ -n "${KUBE_CONTEXT}" ]]; then
  kubectl_args+=(--context "${KUBE_CONTEXT}")
fi

IFS=',' read -r -a deployments <<< "${WORKER_DEPLOYMENTS}"
for deployment in "${deployments[@]}"; do
  [[ -n "${deployment}" ]] || continue
  name="${deployment//[^A-Za-z0-9_.-]/_}"
  kubectl "${kubectl_args[@]}" -n "${NAMESPACE}" \
    rollout status "deployment/${deployment}" --timeout="${ROLLOUT_TIMEOUT}" \
    | tee "${EVIDENCE_DIR}/${name}.rollout.txt"
  kubectl "${kubectl_args[@]}" -n "${NAMESPACE}" \
    get deployment "${deployment}" -o json \
    > "${EVIDENCE_DIR}/${name}.deployment.json"
done

kubectl "${kubectl_args[@]}" -n "${NAMESPACE}" get pods -o wide \
  > "${EVIDENCE_DIR}/pods.txt"
kubectl "${kubectl_args[@]}" -n "${NAMESPACE}" get events --sort-by=.lastTimestamp \
  > "${EVIDENCE_DIR}/events.txt"

cat > "${EVIDENCE_DIR}/README.txt" <<EOF
采集时间(UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)
Namespace: ${NAMESPACE}
说明: 本证据只证明 Deployment rollout 完成、Pod 就绪和 staging 事件没有被命令本身截断。
说明: 任务接管、非终态归零、Kafka lag 归零和业务对账必须结合控制面查询与 worker runbook 继续验收。
EOF
printf 'Worker 滚动升级证据已写入: %s\n' "${EVIDENCE_DIR}"
