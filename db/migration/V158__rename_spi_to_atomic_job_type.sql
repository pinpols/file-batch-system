-- ADR-035 硬切:batch-worker-spi 重命名为 batch-worker-atomic,随之 job_type / task_type 值 'SPI' → 'ATOMIC'。
-- 配套:JobType 枚举 SPI→ATOMIC、Kafka topic batch.task.dispatch.spi→.atomic、worker_group 'spi'→'atomic'。
-- 无双轨:存量行直接迁移,新 CHECK 只含 'ATOMIC'(不再保留 'SPI')。
-- 顺序:先放开旧 CHECK → 迁移存量行 → 装上只含 ATOMIC 的新 CHECK(避免迁移期撞约束)。

ALTER TABLE batch.job_definition DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;
ALTER TABLE batch.job_task DROP CONSTRAINT IF EXISTS ck_job_task_type;

-- 存量数据迁移(热表)
UPDATE batch.job_definition SET job_type = 'ATOMIC' WHERE job_type = 'SPI';
UPDATE batch.job_task SET task_type = 'ATOMIC' WHERE task_type = 'SPI';
-- worker_group 路由值随 worker 注册值一起改('spi' → 'atomic')
UPDATE batch.job_definition SET worker_group = 'atomic' WHERE worker_group = 'spi';

-- 冷表存量同步(archive.job_task_archive 无 CHECK,仅数据一致性)
UPDATE archive.job_task_archive SET task_type = 'ATOMIC' WHERE task_type = 'SPI';

ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'WORKFLOW', 'ATOMIC'));

ALTER TABLE batch.job_task
    ADD CONSTRAINT ck_job_task_type
        CHECK (task_type IN ('EXECUTION', 'COMPENSATION', 'REPLAY', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'GENERAL', 'WORKFLOW', 'ATOMIC'));
