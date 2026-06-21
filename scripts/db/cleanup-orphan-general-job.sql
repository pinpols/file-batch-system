-- cleanup-orphan-general-job.sql
-- ---------------------------------------------------------------
-- 清理两类"永远跑不动"的异常数据：
--
-- 1. 孤儿 job_definition（含其活跃 job_instance / job_partition 级联）
--    判定：enabled=true 且 worker_group 非空 且该 worker_group 无任何
--          status IN ('ONLINE','DRAINING') 的 worker 登记。
--    例：default-tenant 的 gen_archive_purge / gen_data_cleanup /
--        gen_index_rebuild 都声明 worker_group='GENERAL'，但系统只有
--        IMPORT/EXPORT/DISPATCH 组 worker → 每 10s 刷一条 selector WARN。
--
--    注：WORKFLOW 类型的 job_definition 允许 worker_group 为空（workflow
--        本身不挂 worker，节点才分发），worker_group IS NULL/'' 的条目
--        **不**算孤儿。下方 orphan_defs 过滤已排除。
--
-- 2. Stale CREATED job_instance（独立孤立实例）
--    判定：instance_status='CREATED' 且 created_at < now() - 1h。
--    场景：launch 过程在 job_instance 落盘后因并发/窗口/quota 失败，
--          没进入 WAITING 就悬挂；这些实例永远不会被 scheduler 扫起。
--
-- 脚本特点：
--   - 幂等：只改"当前还处于活跃状态"的行，跑多次无副作用
--   - 通用：不硬编码 job_code，用 worker_registry 动态算孤儿
--   - 事务包裹：出错立即回滚
--   - 可审计：每段 UPDATE 前 SELECT 列出将被改的范围
--
-- 用法：
--   PGPASSWORD=<pwd> psql -h localhost -p 15432 -U batch_user \
--       -d batch_platform -f scripts/db/cleanup-orphan-general-job.sql
-- ---------------------------------------------------------------

\set ON_ERROR_STOP on

-- =============================================================
-- PART A — 诊断（只读）：先看清单，再执行 PART B
-- =============================================================

\echo '== [A-1] Orphan enabled job_definitions (worker_group has no online worker) =='
WITH online_groups AS (
  SELECT DISTINCT worker_group
  FROM batch.worker_registry
  WHERE status IN ('ONLINE','DRAINING')
    AND worker_group IS NOT NULL
    AND worker_group <> ''
)
SELECT jd.id, jd.tenant_id, jd.job_code, jd.job_type, jd.worker_group
FROM batch.job_definition jd
WHERE jd.enabled = true
  AND jd.worker_group IS NOT NULL
  AND jd.worker_group <> ''
  AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups)
ORDER BY jd.tenant_id, jd.job_code;

\echo '== [A-2] Active instances on orphan definitions (will be CANCELLED) =='
WITH online_groups AS (
  SELECT DISTINCT worker_group FROM batch.worker_registry
   WHERE status IN ('ONLINE','DRAINING')
     AND worker_group IS NOT NULL AND worker_group <> ''
),
orphan_defs AS (
  SELECT jd.tenant_id, jd.job_code
  FROM batch.job_definition jd
  WHERE jd.enabled = true
    AND jd.worker_group IS NOT NULL AND jd.worker_group <> ''
    AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups)
)
SELECT ji.id, ji.tenant_id, ji.job_code, ji.instance_status, ji.created_at
FROM batch.job_instance ji
JOIN orphan_defs o
  ON o.tenant_id = ji.tenant_id
 AND o.job_code  = ji.job_code
WHERE ji.instance_status IN ('CREATED','WAITING','READY','RUNNING')
ORDER BY ji.created_at;

\echo '== [A-3] Partitions on orphan instances (will be CANCELLED) =='
WITH online_groups AS (
  SELECT DISTINCT worker_group FROM batch.worker_registry
   WHERE status IN ('ONLINE','DRAINING')
     AND worker_group IS NOT NULL AND worker_group <> ''
),
orphan_defs AS (
  SELECT jd.tenant_id, jd.job_code
  FROM batch.job_definition jd
  WHERE jd.enabled = true
    AND jd.worker_group IS NOT NULL AND jd.worker_group <> ''
    AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups)
)
SELECT jp.id, jp.tenant_id, jp.job_instance_id, jp.partition_no, jp.partition_status
FROM batch.job_partition jp
JOIN batch.job_instance ji ON ji.id = jp.job_instance_id
JOIN orphan_defs o
  ON o.tenant_id = ji.tenant_id
 AND o.job_code  = ji.job_code
WHERE jp.partition_status IN ('CREATED','WAITING','READY','RETRYING','RUNNING')
ORDER BY jp.job_instance_id, jp.partition_no;

\echo '== [A-4] Stale CREATED instances (created more than 1 hour ago) =='
SELECT id, tenant_id, job_code, instance_status, created_at
FROM batch.job_instance
WHERE instance_status = 'CREATED'
  AND created_at < now() - interval '1 hour'
ORDER BY created_at;

-- =============================================================
-- PART B — 执行清理（写事务）
-- =============================================================

BEGIN;

-- B-1: 级联 CANCEL orphan 定义下所有活跃 partition
WITH online_groups AS (
  SELECT DISTINCT worker_group FROM batch.worker_registry
   WHERE status IN ('ONLINE','DRAINING')
     AND worker_group IS NOT NULL AND worker_group <> ''
),
orphan_defs AS (
  SELECT jd.tenant_id, jd.job_code
  FROM batch.job_definition jd
  WHERE jd.enabled = true
    AND jd.worker_group IS NOT NULL AND jd.worker_group <> ''
    AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups)
),
orphan_instance_ids AS (
  SELECT ji.id
  FROM batch.job_instance ji
  JOIN orphan_defs o USING (tenant_id, job_code)
  WHERE ji.instance_status IN ('CREATED','WAITING','READY','RUNNING')
)
UPDATE batch.job_partition jp
   SET partition_status = 'CANCELLED',
       finished_at      = now(),
       updated_at       = now(),
       version          = jp.version + 1
  FROM orphan_instance_ids oii
 WHERE jp.job_instance_id = oii.id
   AND jp.partition_status IN ('CREATED','WAITING','READY','RETRYING','RUNNING');

-- B-2: CANCEL orphan 定义下所有活跃 instance
WITH online_groups AS (
  SELECT DISTINCT worker_group FROM batch.worker_registry
   WHERE status IN ('ONLINE','DRAINING')
     AND worker_group IS NOT NULL AND worker_group <> ''
),
orphan_defs AS (
  SELECT jd.tenant_id, jd.job_code
  FROM batch.job_definition jd
  WHERE jd.enabled = true
    AND jd.worker_group IS NOT NULL AND jd.worker_group <> ''
    AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups)
)
UPDATE batch.job_instance ji
   SET instance_status = 'CANCELLED',
       finished_at     = COALESCE(ji.finished_at, now())
  FROM orphan_defs o
 WHERE ji.tenant_id = o.tenant_id
   AND ji.job_code  = o.job_code
   AND ji.instance_status IN ('CREATED','WAITING','READY','RUNNING');

-- B-3: 禁用 orphan job_definition（阻止未来触发再产生新的孤儿实例）
WITH online_groups AS (
  SELECT DISTINCT worker_group FROM batch.worker_registry
   WHERE status IN ('ONLINE','DRAINING')
     AND worker_group IS NOT NULL AND worker_group <> ''
)
UPDATE batch.job_definition jd
   SET enabled    = false,
       updated_at = now()
 WHERE jd.enabled = true
   AND jd.worker_group IS NOT NULL AND jd.worker_group <> ''
   AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups);

-- B-4: 单独收尾 stale CREATED instance（非孤儿但长期未启动的遗留）
--      这些 job_definition 本身有 worker（如 imp_loan_batch），只是 launch
--      阶段中断没进 WAITING。不禁用 definition，只 CANCEL 实例本身。
UPDATE batch.job_instance
   SET instance_status = 'CANCELLED',
       finished_at     = COALESCE(finished_at, now())
 WHERE instance_status = 'CREATED'
   AND created_at < now() - interval '1 hour';

COMMIT;

-- =============================================================
-- PART C — 事后验证
-- =============================================================

\echo '== [C-1] Post-cleanup instance status distribution =='
SELECT instance_status, count(*)
FROM batch.job_instance
GROUP BY instance_status
ORDER BY 1;

\echo '== [C-2] Post-cleanup partition status distribution =='
SELECT partition_status, count(*)
FROM batch.job_partition
GROUP BY partition_status
ORDER BY 1;

\echo '== [C-3] Remaining orphan enabled definitions (should be empty) =='
WITH online_groups AS (
  SELECT DISTINCT worker_group FROM batch.worker_registry
   WHERE status IN ('ONLINE','DRAINING')
     AND worker_group IS NOT NULL AND worker_group <> ''
)
SELECT jd.id, jd.tenant_id, jd.job_code, jd.worker_group
FROM batch.job_definition jd
WHERE jd.enabled = true
  AND jd.worker_group IS NOT NULL AND jd.worker_group <> ''
  AND jd.worker_group NOT IN (SELECT worker_group FROM online_groups);

-- ---------------------------------------------------------------
-- 反向回滚（仅在决定重开某个 job 时使用，非日常）：
--
--   UPDATE batch.job_definition
--      SET enabled=true, updated_at=now()
--    WHERE tenant_id='default-tenant' AND job_code='gen_data_cleanup';
--
--   -- CANCELLED 的 instance/partition 不要重置为 WAITING（无此语义），
--   -- 想重跑请触发一次新的 job_instance（走 Quartz 或 /api/triggers/*）。
-- ---------------------------------------------------------------
