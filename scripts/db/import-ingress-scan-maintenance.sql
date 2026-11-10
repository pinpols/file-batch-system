-- 用途：在停止旧扫描器后，有界重置指定入口扫描范围的可重建协调状态。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v scope_id=范围摘要 -f scripts/db/import-ingress-scan-maintenance.sql
-- 默认只预览；维护阶段依次指定 action=begin、action=clear-page、action=activate。
-- clear-page 每次最多删除 max_rows 行，确认剩余为零后才能启用；不修改文件记录和审计。
-- 危险：clear-page 会删除指定范围的可重建扫描观察记录；执行前必须确认目标库、范围及旧扫描器已停止。
-- 启用后 scanner 重新发现对象；如需还原原观察进度，须从执行前备份恢复。
\set ON_ERROR_STOP on
\if :{?scope_id}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定待维护的 scope_id'; END $$;
\endif
\if :{?action}
\else
  \set action preview
\endif
\if :{?max_rows}
\else
  \set max_rows 200
\endif

BEGIN;

SELECT (:'scope_id' ~ '^[0-9a-f]{64}$') AS valid_scope,
       (:'action' IN ('preview', 'begin', 'clear-page', 'activate')) AS valid_action,
       CASE WHEN :'max_rows' ~ '^[0-9]{1,5}$'
            THEN :'max_rows'::integer BETWEEN 1 AND 10000 ELSE false END AS valid_limit,
       pg_has_role(current_user, (SELECT relowner FROM pg_class
           WHERE oid = 'batch.import_ingress_scan_state'::regclass), 'USAGE') AS is_owner
\gset
\if :valid_scope
\else
  DO $$ BEGIN RAISE EXCEPTION 'scope_id 必须是完整的小写 SHA-256 摘要'; END $$;
\endif
\if :valid_action
\else
  DO $$ BEGIN RAISE EXCEPTION 'action 只能是 preview、begin、clear-page 或 activate'; END $$;
\endif
\if :valid_limit
\else
  DO $$ BEGIN RAISE EXCEPTION 'max_rows 必须在 1 至 10000 之间'; END $$;
\endif
\if :is_owner
\else
  DO $$ BEGIN RAISE EXCEPTION '仅扫描状态表的所有者或其授权成员可执行维护'; END $$;
\endif

SELECT EXISTS (SELECT 1 FROM batch.import_ingress_scan_state WHERE scope_id = :'scope_id') AS scope_exists \gset
\if :scope_exists
\else
  DO $$ BEGIN RAISE EXCEPTION '扫描范围不存在或当前角色不可见'; END $$;
\endif

-- 与扫描器相同的顺序先锁物理存储，再锁前缀范围；不在持锁事务内访问对象存储。
SELECT storage_id FROM batch.import_ingress_scan_state WHERE scope_id = :'scope_id' \gset
SELECT storage_id FROM batch.import_ingress_scan_storage_lease WHERE storage_id = :'storage_id' FOR UPDATE;
SELECT scope_id FROM batch.import_ingress_scan_state WHERE scope_id = :'scope_id' FOR UPDATE;

SELECT s.scope_id, s.bucket_name, s.object_prefix, s.phase, s.cycle, s.fence,
       s.lease_until AS scope_lease_until, l.lease_until AS storage_lease_until,
       (SELECT count(*) FROM (SELECT 1 FROM batch.import_ingress_scan_object o
           WHERE o.scope_id = s.scope_id LIMIT (:'max_rows'::integer + 1)) sample) AS objects_seen_up_to_batch_plus_one
  FROM batch.import_ingress_scan_state s
  JOIN batch.import_ingress_scan_storage_lease l ON l.storage_id = s.storage_id
 WHERE s.scope_id = :'scope_id';

SELECT (:'action' = 'preview') AS is_preview,
       (:'action' = 'begin') AS is_begin,
       (:'action' = 'clear-page') AS is_clear,
       (:'action' = 'activate') AS is_activate,
       (s.phase = 'MAINTENANCE') AS in_maintenance,
       (s.phase = 'DISCOVER' AND s.config_digest IS NULL AND s.lease_owner IS NULL) AS already_activated,
       (s.lease_until IS NULL OR s.lease_until <= clock_timestamp())
         AND (l.lease_until IS NULL OR l.lease_until <= clock_timestamp()) AS no_live_lease,
       NOT EXISTS (SELECT 1 FROM batch.import_ingress_scan_object o WHERE o.scope_id = s.scope_id) AS is_empty
  FROM batch.import_ingress_scan_state s
  JOIN batch.import_ingress_scan_storage_lease l ON l.storage_id = s.storage_id
 WHERE s.scope_id = :'scope_id' \gset

\if :is_preview
  \echo '预览完成；没有修改扫描状态。'
\else
  \if :no_live_lease
  \else
    DO $$ BEGIN RAISE EXCEPTION '存储桶或扫描范围仍有有效租约，先停止扫描器并等待租约结束'; END $$;
  \endif
  \if :is_begin
    UPDATE batch.import_ingress_scan_state
       SET phase = 'MAINTENANCE', lease_owner = NULL, lease_until = NULL,
           waiting_since = NULL, waiting_until = NULL,
           fence = fence + 1, updated_at = clock_timestamp()
     WHERE scope_id = :'scope_id' AND phase <> 'MAINTENANCE';
  \elif :is_clear
    \if :in_maintenance
    \else
      DO $$ BEGIN RAISE EXCEPTION '必须先进入 MAINTENANCE'; END $$;
    \endif
    DELETE FROM batch.import_ingress_scan_object WHERE id IN (
        SELECT id FROM batch.import_ingress_scan_object
         WHERE scope_id = :'scope_id' ORDER BY seen_cycle, id LIMIT :'max_rows'::integer
    );
    SELECT count(*) AS remaining_seen_up_to_batch_plus_one FROM (
        SELECT 1 FROM batch.import_ingress_scan_object
         WHERE scope_id = :'scope_id' LIMIT (:'max_rows'::integer + 1)
    ) sample;
  \elif :is_activate
    \if :in_maintenance
      \if :is_empty
      \else
        DO $$ BEGIN RAISE EXCEPTION '扫描观察记录尚未清空，不能启用'; END $$;
      \endif
      UPDATE batch.import_ingress_scan_state
         SET phase = 'DISCOVER', cycle = 1, list_marker = NULL, after_object_id = 0,
             window_id = 0, revisit_marker = NULL, revisit_budget = 1, revisit_remaining = 1,
             next_lane = 'FORWARD', cleanup_required = FALSE, apply_after_id = 0, apply_upper_id = 0,
             config_digest = NULL, lease_owner = NULL, lease_until = NULL,
             waiting_since = NULL, waiting_until = NULL,
             fence = fence + 1, updated_at = clock_timestamp()
       WHERE scope_id = :'scope_id';
    \elif :already_activated
      \if :is_empty
        \echo '扫描范围已启用且尚未绑定新配置。'
      \else
        DO $$ BEGIN RAISE EXCEPTION '扫描观察记录尚未清空，不能启用'; END $$;
      \endif
    \else
      DO $$ BEGIN RAISE EXCEPTION '必须先进入 MAINTENANCE'; END $$;
    \endif
  \endif
\endif

COMMIT;
