#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"

APPLY=false
DIAGNOSE=false
CLEAN_KAFKA=false
RESET_KAFKA_TOPICS=false
CLEAN_MINIO=false
CLEAN_MINIO_TRASH=false
POSTGRES_MAINTENANCE=false
POSTGRES_RECLAIM=false
RUN_ID_FILTER="${RUN_ID:-}"
LOAD_BIZ_DATE="${BIZ_DATE:-2026-05-05}"
KAFKA_CONTAINER_NAME="${KAFKA_CONTAINER_NAME:-$BATCH_DEFAULT_KAFKA_CONTAINER}"
POSTGRES_CONTAINER_NAME="${POSTGRES_CONTAINER_NAME:-$BATCH_DEFAULT_POSTGRES_CONTAINER}"
MINIO_BUCKET_NAME="${MINIO_BUCKET:-$BATCH_DEFAULT_MINIO_BUCKET}"
KAFKA_TOPICS_CSV="${KAFKA_TOPICS_CSV:-batch.trigger.launch.v1,batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,batch.task.dispatch.dispatch,batch.task.dispatch.atomic,batch.task.result,batch.task.retry,batch.task.dead-letter}"
LOAD_TEST_KAFKA_RETENTION_MS="${LOAD_TEST_KAFKA_RETENTION_MS:-21600000}"
LOAD_TEST_KAFKA_SEGMENT_MS="${LOAD_TEST_KAFKA_SEGMENT_MS:-300000}"
LOAD_TEST_KAFKA_SEGMENT_BYTES="${LOAD_TEST_KAFKA_SEGMENT_BYTES:-67108864}"

usage() {
  cat <<'EOF'
用法: bash load-tests/scripts/cleanup-load-test-environment.sh [选项]

默认只诊断，不删除数据。所有删除动作都需要 --apply。

选项:
  --diagnose                 输出 Docker / Kafka / MinIO / PostgreSQL 占用
  --apply                    执行已选择的清理动作
  --all                      执行推荐的本地压测后清理：Kafka 安全保留、MinIO 压测前缀、PostgreSQL VACUUM
  --run-id <RUN_ID>          仅用于提示和已知对象名匹配，业务表清理仍请用 cleanup-worker-load-data.sh
  --biz-date <YYYY-MM-DD>    MinIO 导出压测前缀日期，默认 2026-05-05
  --kafka                    将本地压测 topic 调整为安全保留，等待 broker 回收日志段
  --kafka-reset-topics       删除并重建本地压测 topic，立刻释放 Kafka topic 日志；只适合可重建的本地环境
  --minio                    清理本地 MinIO 压测前缀
  --minio-trash              清理本地 MinIO 删除回收站；只适合可重建的本地环境
  --postgres-maintenance     对压测高频表执行 VACUUM ANALYZE
  --postgres-reclaim         对压测高频表执行 VACUUM FULL，需停写且可能锁表；不会被 --all 默认启用
  -h, --help                 显示帮助

示例:
  bash load-tests/scripts/cleanup-load-test-environment.sh --diagnose
  bash load-tests/scripts/cleanup-load-test-environment.sh --apply --all
  RUN_ID=ltw-20261004094022 bash load-tests/scripts/cleanup-worker-load-data.sh
  bash load-tests/scripts/cleanup-load-test-environment.sh --apply --kafka-reset-topics
EOF
}

require_value() {
  if [[ "$#" -lt 2 || -z "${2:-}" ]]; then
    echo "缺少参数值: $1" >&2
    exit 2
  fi
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --diagnose)
      DIAGNOSE=true
      ;;
    --apply)
      APPLY=true
      ;;
    --all)
      CLEAN_KAFKA=true
      CLEAN_MINIO=true
      CLEAN_MINIO_TRASH=true
      POSTGRES_MAINTENANCE=true
      ;;
    --run-id)
      require_value "$@"
      RUN_ID_FILTER="$2"
      shift
      ;;
    --biz-date)
      require_value "$@"
      LOAD_BIZ_DATE="$2"
      shift
      ;;
    --kafka)
      CLEAN_KAFKA=true
      ;;
    --kafka-reset-topics)
      RESET_KAFKA_TOPICS=true
      CLEAN_KAFKA=true
      ;;
    --minio)
      CLEAN_MINIO=true
      ;;
    --minio-trash)
      CLEAN_MINIO_TRASH=true
      ;;
    --postgres-maintenance)
      POSTGRES_MAINTENANCE=true
      ;;
    --postgres-reclaim)
      POSTGRES_RECLAIM=true
      POSTGRES_MAINTENANCE=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$DIAGNOSE" == "false" && "$CLEAN_KAFKA" == "false" && "$CLEAN_MINIO" == "false" && "$CLEAN_MINIO_TRASH" == "false" && "$POSTGRES_MAINTENANCE" == "false" ]]; then
  DIAGNOSE=true
fi

run_or_preview() {
  if [[ "$APPLY" == "true" ]]; then
    "$@"
  else
    printf '[预览]'
    printf ' %q' "$@"
    printf '\n'
  fi
}

require_docker() {
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    echo "Docker 不可用，无法清理本地压测环境" >&2
    exit 2
  fi
}

kafka_cli() {
  docker exec "$KAFKA_CONTAINER_NAME" "${KAFKA_CONTAINER_BIN_DIR:-$BATCH_DEFAULT_KAFKA_CONTAINER_BIN_DIR}/kafka-topics.sh" \
    --bootstrap-server "$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP" "$@"
}

kafka_configs_cli() {
  docker exec "$KAFKA_CONTAINER_NAME" "${KAFKA_CONTAINER_BIN_DIR:-$BATCH_DEFAULT_KAFKA_CONTAINER_BIN_DIR}/kafka-configs.sh" \
    --bootstrap-server "$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP" "$@"
}

kafka_topic_partitions() {
  local topic="$1"
  kafka_cli --describe --topic "$topic" 2>/dev/null \
    | awk -F'PartitionCount: ' 'NF > 1 { split($2, a, " "); print a[1]; exit }'
}

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

minio_mc() {
  "$ROOT_DIR/scripts/lib/minio-mc.sh" "$@"
}

diagnose() {
  echo "磁盘:"
  df -h "$ROOT_DIR" | tail -n 1
  echo
  echo "Docker:"
  docker system df || true
  echo
  echo "Kafka 目录:"
  docker exec "$KAFKA_CONTAINER_NAME" sh -lc \
    'du -sh /tmp/kafka-logs /var/lib/kafka/data /opt/kafka/logs 2>/dev/null || true; find /tmp/kafka-logs -maxdepth 1 -type d -name "batch.*" -exec du -sh {} + 2>/dev/null | sort -h | tail -n 20' \
    || true
  echo
  echo "MinIO:"
  docker exec "${MINIO_CONTAINER:-$BATCH_DEFAULT_MINIO_CONTAINER}" sh -lc \
    'du -sh /bitnami/minio/data /data 2>/dev/null || true; find /bitnami/minio/data/.minio.sys/tmp -maxdepth 2 -type d -exec du -sh {} + 2>/dev/null | sort -h | tail -n 10' \
    || true
  echo
  echo "PostgreSQL:"
  docker exec "$POSTGRES_CONTAINER_NAME" sh -lc 'du -sh "$PGDATA" "$PGDATA/pg_wal" 2>/dev/null || true' || true
  psql_platform -P pager=off -c "select schemaname, relname, pg_size_pretty(pg_total_relation_size((quote_ident(schemaname)||chr(46)||quote_ident(relname))::regclass)) as total_size from pg_stat_user_tables order by pg_total_relation_size((quote_ident(schemaname)||chr(46)||quote_ident(relname))::regclass) desc limit 12;" || true
}

clean_kafka() {
  IFS=',' read -r -a topics <<< "$KAFKA_TOPICS_CSV"
  if [[ "$RESET_KAFKA_TOPICS" == "true" ]]; then
    echo "Kafka topic reset: ${KAFKA_TOPICS_CSV}"
    for topic in "${topics[@]}"; do
      topic="$(echo "$topic" | xargs)"
      [[ -n "$topic" ]] || continue
      run_or_preview kafka_cli --delete --if-exists --topic "$topic"
    done
    if [[ "$APPLY" == "true" ]]; then
      echo "等待 Kafka 完成 topic 删除..."
      sleep 10
      for topic in "${topics[@]}"; do
        topic="$(echo "$topic" | xargs)"
        [[ -n "$topic" ]] || continue
        local partitions
        case "$topic" in
          batch.trigger.launch.v1)
            partitions="${KAFKA_PARTITIONS_TRIGGER_LAUNCH:-12}" ;;
          batch.task.result)
            partitions="${KAFKA_PARTITIONS_RESULT:-${KAFKA_DEFAULT_PARTITIONS:-4}}" ;;
          batch.task.retry)
            partitions="${KAFKA_PARTITIONS_RETRY:-${KAFKA_DEFAULT_PARTITIONS:-4}}" ;;
          batch.task.dead-letter)
            partitions="${KAFKA_PARTITIONS_DEAD_LETTER:-${KAFKA_DEFAULT_PARTITIONS:-4}}" ;;
          *)
            partitions="${KAFKA_PARTITIONS_DISPATCH:-${KAFKA_DEFAULT_PARTITIONS:-4}}" ;;
        esac
        kafka_cli --create --if-not-exists --topic "$topic" --partitions "$partitions" --replication-factor 1
        current_partitions="$(kafka_topic_partitions "$topic")"
        if [[ -n "$current_partitions" && "$current_partitions" -lt "$partitions" ]]; then
          kafka_cli --alter --topic "$topic" --partitions "$partitions"
        fi
      done
    else
      echo "[预览] 删除后会通过 batch-kafka 容器内 kafka-topics.sh 重建 topic"
    fi
    return
  fi

  echo "Kafka 安全保留配置: ${KAFKA_TOPICS_CSV}"
  for topic in "${topics[@]}"; do
    topic="$(echo "$topic" | xargs)"
    [[ -n "$topic" ]] || continue
    run_or_preview kafka_configs_cli --alter --entity-type topics --entity-name "$topic" \
      --add-config "retention.ms=${LOAD_TEST_KAFKA_RETENTION_MS},segment.ms=${LOAD_TEST_KAFKA_SEGMENT_MS},segment.bytes=${LOAD_TEST_KAFKA_SEGMENT_BYTES}"
  done
  echo "说明: 安全保留不会立刻删除所有旧段；需要等待 broker log cleaner/retention check。要立即释放本地空间，用 --kafka-reset-topics。"
}

clean_minio() {
  local -a prefixes=(
    "outbound/export_settlement_job/${LOAD_BIZ_DATE}"
    "exports/load-test"
    "import/load-test"
    "imports/load-test"
    "load-test"
    "tmp/load-test"
  )
  echo "MinIO 压测前缀 bucket=${MINIO_BUCKET_NAME}"
  for prefix in "${prefixes[@]}"; do
    if [[ "$APPLY" == "true" ]]; then
      minio_mc rm --recursive --force "${MINIO_MC_ALIAS:-local}/${MINIO_BUCKET_NAME}/${prefix}" >/dev/null || true
      echo "已清理 MinIO 前缀: ${MINIO_BUCKET_NAME}/${prefix}"
    else
      echo "[预览] minio rm --recursive --force ${MINIO_MC_ALIAS:-local}/${MINIO_BUCKET_NAME}/${prefix}"
      minio_mc ls --recursive "${MINIO_MC_ALIAS:-local}/${MINIO_BUCKET_NAME}/${prefix}" 2>/dev/null | tail -n 5 || true
    fi
  done
  if [[ -n "$RUN_ID_FILTER" ]]; then
    echo "RUN_ID=${RUN_ID_FILTER} 的对象名残留请通过上方前缀确认；业务表仍使用 cleanup-worker-load-data.sh 精确清理。"
  fi
}

clean_minio_trash() {
  local trash_dir="/bitnami/minio/data/.minio.sys/tmp/.trash"
  echo "MinIO 本地删除回收站:"
  docker exec "${MINIO_CONTAINER:-$BATCH_DEFAULT_MINIO_CONTAINER}" sh -lc "du -sh '$trash_dir' 2>/dev/null || true"
  if [[ "$APPLY" == "true" ]]; then
    docker exec "${MINIO_CONTAINER:-$BATCH_DEFAULT_MINIO_CONTAINER}" sh -lc "find '$trash_dir' -mindepth 1 -maxdepth 1 -exec rm -rf {} +" || true
    echo "已清理 MinIO 本地删除回收站"
  else
    echo "[预览] docker exec ${MINIO_CONTAINER:-$BATCH_DEFAULT_MINIO_CONTAINER} rm -rf ${trash_dir}/*"
  fi
}

postgres_maintenance() {
  local tables=(
    batch.trigger_request
    batch.trigger_outbox_event
    batch.job_instance
    batch.job_task
    batch.job_partition
    batch.job_step_instance
    batch.outbox_event
    batch.event_delivery_log
    batch.file_record
    batch.file_dispatch_record
    batch.result_version
  )
  for table in "${tables[@]}"; do
    run_or_preview psql_platform -c "VACUUM (ANALYZE) ${table};"
  done
  run_or_preview psql_platform -c "CHECKPOINT;"
  if [[ "$POSTGRES_RECLAIM" == "true" ]]; then
    echo "VACUUM FULL 会锁表，仅限本地停写窗口。"
    for table in "${tables[@]}"; do
      run_or_preview psql_platform -c "VACUUM (FULL, ANALYZE) ${table};"
    done
  fi
}

require_docker
echo "模式: $([[ "$APPLY" == "true" ]] && echo 执行 || echo 预览)"
echo "RUN_ID_FILTER=${RUN_ID_FILTER:-<none>} BIZ_DATE=${LOAD_BIZ_DATE}"

if [[ "$DIAGNOSE" == "true" ]]; then
  diagnose
fi
if [[ "$CLEAN_KAFKA" == "true" ]]; then
  clean_kafka
fi
if [[ "$CLEAN_MINIO" == "true" ]]; then
  clean_minio
fi
if [[ "$CLEAN_MINIO_TRASH" == "true" ]]; then
  clean_minio_trash
fi
if [[ "$POSTGRES_MAINTENANCE" == "true" ]]; then
  postgres_maintenance
fi

echo
echo "完成。压测 runId 业务数据清理仍使用: RUN_ID=<id> bash load-tests/scripts/cleanup-worker-load-data.sh"
