#!/usr/bin/env bash
# 进入独立运维工具箱；不把 psql、Kafka CLI、mc、Python 塞进应用容器。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"
if [[ ! -f "$ENV_FILE" ]]; then
  ENV_FILE=".env.example"
fi

if (($# == 0)); then
  set -- bash
fi

exec docker compose --env-file "$ENV_FILE" --profile ops run --rm ops-toolbox "$@"
