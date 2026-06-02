#!/usr/bin/env bash
# batch-worker-sdk-template — 本地一键启动
#
# 用法:
#   cp .env.example .env && vi .env           # 填占位
#   ./run.sh                                   # docker build + run
#   ./run.sh --no-build                        # 复用上次镜像直接 run
#   ./run.sh --logs                            # follow 日志
set -euo pipefail

IMAGE="batch-worker-sdk-template:dev"
CONTAINER="batch-worker-sdk-template"
ENV_FILE=".env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[run.sh] 缺 ${ENV_FILE} —— 先 cp .env.example .env 并改填占位" >&2
  exit 1
fi

NO_BUILD=0
LOGS=0
for arg in "$@"; do
  case "${arg}" in
    --no-build) NO_BUILD=1 ;;
    --logs) LOGS=1 ;;
    *) echo "[run.sh] 未知参数:${arg}" >&2; exit 1 ;;
  esac
done

if [[ "${NO_BUILD}" -eq 0 ]]; then
  echo "[run.sh] docker build -> ${IMAGE}"
  docker build -t "${IMAGE}" .
fi

docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[run.sh] docker run ${CONTAINER}"
docker run -d \
  --name "${CONTAINER}" \
  --env-file "${ENV_FILE}" \
  --restart unless-stopped \
  "${IMAGE}"

if [[ "${LOGS}" -eq 1 ]]; then
  docker logs -f "${CONTAINER}"
else
  echo "[run.sh] 启动完成。docker logs -f ${CONTAINER} 看日志,docker stop ${CONTAINER} 停。"
fi
