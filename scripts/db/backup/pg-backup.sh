#!/usr/bin/env bash
# =========================================================
# pg-backup.sh — PostgreSQL 每日基准备份 + 两库逻辑导出(+ 可选 WAL 归档清理)
#
# 对应 docs/runbook/backup-and-pitr.md §1.2 / §1.3 的「三层备份」中的 A(物理 base)
# 与 C(逻辑 dump)。B(WAL 连续归档)由 PostgreSQL 的 archive_command 实时驱动,不在本脚本;
# 本脚本可选地做 WAL 归档目录的保留期清理(--prune-wal)。
#
# 设计:**dev 默认不依赖、不影响**。这是 ops/cron 落地脚本,生产由运维按 §1.3 cron 编排;
# 本地若要跑一次性逻辑恢复演练用 dr-drill.sh(走 docker exec,无需本机 pg 客户端)。
#
# 依赖(生产宿主或 sidecar 容器内):pg_basebackup / pg_dump / psql(与目标库同大版本)。
#
# 用法:
#   BACKUP_DEST=/mnt/backup PGHOST=pg-primary.db.svc \
#   POSTGRES_PASSWORD=*** bash scripts/db/backup/pg-backup.sh
#
#   # S3 目标(需 aws cli):
#   BACKUP_DEST=s3://batch-backups UPLOADER=s3 ... bash scripts/db/backup/pg-backup.sh
#
#   # 只跑逻辑导出(跳过物理 base,如 base 由独立工具/快照负责):
#   SKIP_BASEBACKUP=1 ... bash scripts/db/backup/pg-backup.sh
#
# 退出码:0 全部成功;非 0 任一关键步骤失败(cron 应据此告警)。
# =========================================================
set -euo pipefail

# ---- 配置(env 注入,带合理默认)----
TS=$(date -u +%Y%m%dT%H%M%SZ)

BACKUP_DEST=${BACKUP_DEST:?需指定 BACKUP_DEST(如 /mnt/backup 或 s3://bucket/prefix)}
PGHOST=${PGHOST:?需指定 PGHOST(主库地址,生产连 VIP/svc 名,不连 IP)}
PGPORT=${PGPORT:-5432}
POSTGRES_USER=${POSTGRES_USER:-batch_user}
POSTGRES_REPLICATION_USER=${POSTGRES_REPLICATION_USER:-replicator}
export PGPASSWORD=${POSTGRES_PASSWORD:?需指定 POSTGRES_PASSWORD}

# 要逻辑导出的库(两库都备 —— 只备 platform 会丢全部业务数据,见 runbook §0)。
PLATFORM_DB=${PLATFORM_DB:-batch_platform}
BUSINESS_DB=${BUSINESS_DB:-batch_business}

# 保留策略(天)。按合规要求调。
BASE_RETENTION_DAYS=${BASE_RETENTION_DAYS:-14}
LOGICAL_RETENTION_DAYS=${LOGICAL_RETENTION_DAYS:-35}
WAL_RETENTION_DAYS=${WAL_RETENTION_DAYS:-14}

# 上传方式:cp(本地/NFS 直接 cp)| s3(aws s3 cp)。默认按 BACKUP_DEST 前缀自动判。
UPLOADER=${UPLOADER:-auto}
WORK_DIR=${WORK_DIR:-/tmp}

SKIP_BASEBACKUP=${SKIP_BASEBACKUP:-0}
PRUNE_WAL=${PRUNE_WAL:-0}            # 设 1 时清理 WAL_ARCHIVE_DIR 下过期段
WAL_ARCHIVE_DIR=${WAL_ARCHIVE_DIR:-}

PUSHGATEWAY_URL=${PUSHGATEWAY_URL:-}  # 设了则备份成功后 push 新鲜度指标(runbook §3)

log() { printf '[pg-backup %s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }

if [[ "$UPLOADER" == "auto" ]]; then
  case "$BACKUP_DEST" in
    s3://*) UPLOADER="s3" ;;
    *) UPLOADER="cp" ;;
  esac
fi

# $1=本地文件 $2=目标相对路径(拼到 BACKUP_DEST 下)
upload() {
  local src="$1" dest="${BACKUP_DEST%/}/$2"
  case "$UPLOADER" in
    s3) aws s3 cp "$src" "$dest" ;;
    cp) mkdir -p "$(dirname "$dest")"; cp "$src" "$dest" ;;
    *) log "未知 UPLOADER=$UPLOADER"; return 1 ;;
  esac
}

# 按修改时间清理本地/NFS 目录下过期文件(仅 cp 模式;s3 用 lifecycle policy 治理,见 minio-lifecycle-policy.md)。
prune_older_than() {
  local dir="$1" days="$2"
  [[ "$UPLOADER" == "cp" && -d "$dir" ]] || return 0
  find "$dir" -type f -mtime "+${days}" -print -delete 2>/dev/null || true
}

# ---- A. 物理基准备份(pg_basebackup,含 PITR 所需 backup_label)----
if [[ "$SKIP_BASEBACKUP" != "1" ]]; then
  log "物理基准备份(pg_basebackup -Ft -z -Xs)..."
  base_file="$WORK_DIR/base-$TS.tar.gz"
  pg_basebackup -h "$PGHOST" -p "$PGPORT" -U "$POSTGRES_REPLICATION_USER" \
    -D - -Ft -z -Xs -P -l "base-$TS" > "$base_file"
  upload "$base_file" "base/base-$TS.tar.gz"
  rm -f "$base_file"
  log "  ✓ base → ${BACKUP_DEST%/}/base/base-$TS.tar.gz"
else
  log "SKIP_BASEBACKUP=1 → 跳过物理 base(由独立快照/工具负责)"
fi

# ---- C. 逻辑导出(两库各一份,custom format 支持 -j 并行恢复 + 单表 -t)----
for DB in "$PLATFORM_DB" "$BUSINESS_DB"; do
  log "逻辑导出 $DB(pg_dump -Fc -Z6)..."
  dump_file="$WORK_DIR/$DB-$TS.dump"
  pg_dump -h "$PGHOST" -p "$PGPORT" -U "$POSTGRES_USER" -Fc -Z6 -f "$dump_file" "$DB"
  upload "$dump_file" "logical/$DB-$TS.dump"
  rm -f "$dump_file"
  log "  ✓ $DB → ${BACKUP_DEST%/}/logical/$DB-$TS.dump"
done

# ---- 可选:WAL 归档保留期清理 ----
if [[ "$PRUNE_WAL" == "1" && -n "$WAL_ARCHIVE_DIR" ]]; then
  log "清理 WAL 归档 > ${WAL_RETENTION_DAYS}d($WAL_ARCHIVE_DIR)..."
  prune_older_than "$WAL_ARCHIVE_DIR" "$WAL_RETENTION_DAYS"
fi

# ---- 保留策略清理(cp 模式)----
prune_older_than "${BACKUP_DEST%/}/base" "$BASE_RETENTION_DAYS"
prune_older_than "${BACKUP_DEST%/}/logical" "$LOGICAL_RETENTION_DAYS"

# ---- 备份新鲜度指标(runbook §3 / PostgresBackupStale 告警)----
if [[ -n "$PUSHGATEWAY_URL" ]]; then
  now=$(date +%s)
  if cat <<EOF | curl -fsS --data-binary @- "${PUSHGATEWAY_URL%/}/metrics/job/pg-backup" >/dev/null; then
# TYPE batch_pg_last_successful_backup_timestamp_seconds gauge
batch_pg_last_successful_backup_timestamp_seconds $now
EOF
    log "  ✓ 已 push 备份新鲜度指标(ts=$now)"
  else
    log "  ⚠ 推送新鲜度指标失败(不阻断备份本身,但 PostgresBackupStale 可能误报)"
  fi
fi

log "✅ 备份完成(base + 两库逻辑 dump)。目标:$BACKUP_DEST"
