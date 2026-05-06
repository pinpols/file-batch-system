-- =====================================================================
-- V105: batch_day_instance 增加 SETTLING 中间态
-- =====================================================================
-- 背景:
--   原状态机 OPEN -> CUTOFF -> SETTLED/FAILED 一步到位; 从 settle 调度
--   器启动到事务提交之间运维看不到"settle 进行中"状态, 进程崩溃后无法
--   区分"还没 settle"和"settle 中途断了"。
--
-- 语义:
--   CUTOFF / IN_FLIGHT 上 settle 调度器先 CAS 到 SETTLING (tx1), 再独立
--   事务里读 metrics 决定 SETTLED / FAILED / 回 IN_FLIGHT (tx2)。
--   tx1 已提交但 tx2 没跑/崩溃 -> 行停在 SETTLING; 下一轮 settle 扫描
--   会再读 metrics 重做 finalize, 幂等。
-- =====================================================================

ALTER TABLE batch.batch_day_instance DROP CONSTRAINT IF EXISTS ck_batch_day_instance_status;
ALTER TABLE batch.batch_day_instance ADD CONSTRAINT ck_batch_day_instance_status
    CHECK (day_status IN (
        'OPEN', 'CUTOFF', 'IN_FLIGHT', 'SETTLING', 'SETTLED', 'FAILED', 'SKIPPED', 'MANUAL_RELEASED'
    ));
