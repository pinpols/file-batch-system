#!/usr/bin/env bash
# 业务库初始化公共入口。只能被 source，不要直接执行。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/lib/business-db-bootstrap.sh must be sourced, not executed" >&2
  exit 2
fi

batch_bootstrap_business_database() {
  local root="$1"
  local compose_env_file="$2"
  local compose_project_name="$3"
  local pg_container="${PG_CONTAINER:-batch-postgres-primary}"

  echo "==> 启动 PostgreSQL，准备业务库 DDL/RLS..."
  COMPOSE_IGNORE_ORPHANS=1 docker compose \
    --project-name "$compose_project_name" \
    --env-file "$compose_env_file" \
    -f "$root/docker-compose.yml" \
    up -d --no-recreate postgres-primary >/dev/null

  local attempt
  for attempt in $(seq 1 60); do
    if docker exec "$pg_container" pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" -eq 60 ]]; then
      echo "ERROR: PostgreSQL 在业务库 bootstrap 超时: $pg_container" >&2
      return 1
    fi
    sleep 2
  done

  echo "==> 应用业务库 DDL（biz.* + batch.process_staging）..."
  if ! docker exec -i "$pg_container" psql -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 \
      < "$root/scripts/db/business/create_biz_tables.sql" >/dev/null; then
    echo "ERROR: 业务库 DDL apply 失败（详见 docker logs $pg_container）" >&2
    return 1
  fi

  echo "==> 应用业务库 RLS（roles / grants / tenant policies）..."
  if ! docker exec -i "$pg_container" psql -U "$PGUSER" -d "$BUSINESS_DB" \
      -v ON_ERROR_STOP=1 \
      -v writer_password="${BIZ_WRITER_PASSWORD:-$POSTGRES_PASSWORD}" \
      -v admin_password="${BIZ_ADMIN_PASSWORD:-$POSTGRES_PASSWORD}" \
      < "$root/scripts/db/business/rls-phase-a.sql" >/dev/null; then
    echo "ERROR: 业务库 RLS apply 失败" >&2
    return 1
  fi
}
