-- =============================================================================
-- V150: JSONB partial GIN 索引 + ANALYZE 触发统计 (DBA-2026-05-21 P0-2)
--
-- 背景:
--   V133 已对 batch.file_record.metadata_json 加了**全表** GIN
--   (idx_file_record_metadata_json_gin)。但生产 SQL 审计发现 file_record 表
--   metadata_json IS NULL 占行数约 60%,planner 在评估含
--   `WHERE metadata_json IS NOT NULL AND metadata_json @> ...` 的查询时,
--   选择性估算偏低 → 走 seq scan 而不是已建好的 GIN。
--
--   partial index `WHERE metadata_json IS NOT NULL` 让 planner 的 selectivity
--   估算更接近真实 — 索引行数缩到只覆盖 "有 metadata 的子集",hit ratio 高,
--   planner 更倾向走索引;同时索引体积也下降,vacuum / bloat 改善。
--
--   batch.outbox_event.payload_json 同样是 JSONB NOT NULL — 由于 NOT NULL,
--   partial WHERE 不会过滤掉任何行,意义不大,但保留索引让 ConsoleOutboxMapper
--   按 payload JSON 字段过滤(如 event_type 嵌套字段)时可用。如果生产无相关
--   查询场景,后续可在 V151+ 用 idx_scan 取证后 DROP。
--
--   batch.trigger_outbox_event.payload 实际为 JSONB(V80),非任务描述的 text。
--   本次按任务清单只处理 file_record 与 outbox_event,trigger_outbox_event 留待
--   后续单独评估(其访问模式以 status + next_publish_at 为主,JSON 内容过滤少见)。
--
-- 注意:
--   - Flyway 默认事务包裹,CONCURRENTLY 不可用。生产灰度需先用 manual psql
--     CREATE INDEX CONCURRENTLY ... 旁路建索引,然后让本 migration 用
--     `IF NOT EXISTS` 幂等跳过。
--   - ANALYZE 在事务内可执行(只读统计,不锁数据),让索引统计立即生效;否则
--     需等 autovacuum 触发,可能数小时延迟,期间 planner 仍走老 plan。
-- =============================================================================

-- 1) file_record.metadata_json partial GIN — 仅覆盖 NOT NULL 子集
--    与 V133 idx_file_record_metadata_json_gin (全表) 并存:
--    planner 在 WHERE metadata_json IS NOT NULL 谓词出现时优先 partial,
--    其它查询(如全表 jsonb_exists)仍可走全表 GIN。
CREATE INDEX IF NOT EXISTS idx_file_record_metadata_json_gin_partial
    ON batch.file_record USING GIN (metadata_json)
    WHERE metadata_json IS NOT NULL;

-- 2) outbox_event.payload_json GIN — outbox 表 JSONB NOT NULL,partial 退化为全表
--    单独建索引(不带 partial WHERE)覆盖 ConsoleOutboxMapper 可能的 payload 内
--    字段过滤路径。如生产无此查询,V151+ 取证 DROP。
CREATE INDEX IF NOT EXISTS idx_outbox_event_payload_json_gin
    ON batch.outbox_event USING GIN (payload_json);

-- 3) 立即 ANALYZE 让 planner 拿到新索引统计;否则等 autovacuum 可能数小时。
ANALYZE batch.file_record;
ANALYZE batch.outbox_event;
