-- V64: 统一历史数据中的 code 字段大小写与分隔符，配合入口 CodeNormalizer 防新污染。
--
-- 归一规则（与 batch-common/utils/CodeNormalizer 保持一致）：
--   分组码（worker_group / tenant_id 相关）→ UPPER
--   配置码（window_code / calendar_code / queue_code / channel_code / template_code 等）→ LOWER + '-' 替换为 '_'
--
-- 背景：历史上前端 Excel / REST 入口既不归一也不校验，导致 job_definition 写 'IMPORT'、
-- worker_registry 写 'import'、window_code 既有 'always_open' 又有 'always-open'，WorkerSelector
-- 等值比较时直接失配，任务永远卡 WAITING。

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- worker_group 统一大写
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE batch.worker_registry     SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.job_definition      SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.job_instance        SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.job_partition       SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.resource_queue      SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.workflow_node       SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);

-- ─────────────────────────────────────────────────────────────────────────────
-- window_code / calendar_code / queue_code 统一小写 + 下划线
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE batch.batch_window        SET window_code   = replace(lower(window_code),   '-', '_') WHERE window_code   IS NOT NULL;
UPDATE batch.business_calendar   SET calendar_code = replace(lower(calendar_code), '-', '_') WHERE calendar_code IS NOT NULL;
UPDATE batch.resource_queue      SET queue_code    = replace(lower(queue_code),    '-', '_') WHERE queue_code    IS NOT NULL;

-- job_definition 外键侧
UPDATE batch.job_definition      SET window_code   = replace(lower(window_code),   '-', '_') WHERE window_code   IS NOT NULL;
UPDATE batch.job_definition      SET calendar_code = replace(lower(calendar_code), '-', '_') WHERE calendar_code IS NOT NULL;
UPDATE batch.job_definition      SET queue_code    = replace(lower(queue_code),    '-', '_') WHERE queue_code    IS NOT NULL;

-- job_instance / workflow_node 的引用
UPDATE batch.job_instance        SET queue_code    = replace(lower(queue_code),    '-', '_') WHERE queue_code    IS NOT NULL;
UPDATE batch.workflow_node       SET window_code   = replace(lower(window_code),   '-', '_') WHERE window_code   IS NOT NULL;

COMMIT;
