-- 清理"永久失败"残留死信(畸形输入/坏 SQL/缺表),配合 DefaultRetryGovernanceService 把
-- IMPORT_PARSE_FAILED / IMPORT_PARSE_EMPTY / IMPORT_LOAD_FAILED 归类为 BUSINESS(不再自动重试)。
-- 背景:这些码原漏出 NON_RETRYABLE_ERROR_CODES → 被判 SYSTEM → 永久失败任务在 dead_letter 无限循环
--      (实测 3 个夹具 → 653 行、replay_count 恒 1、give-up 永不触发)。代码修复后不再新增,本脚本清存量。
-- 用法:psql -d <平台库/coordinator> -f scripts/db/cleanup/purge-permanent-deadletters.sql
-- 注:必须在带修复的 orchestrator 重启 后 跑,否则旧实例会继续重新生成。
DELETE FROM batch.dead_letter_task
WHERE dead_letter_reason LIKE '%IMPORT_PARSE_FAILED%'
   OR dead_letter_reason LIKE '%IMPORT_LOAD_FAILED%'
   OR dead_letter_reason LIKE '%IMPORT_PARSE_EMPTY%';
