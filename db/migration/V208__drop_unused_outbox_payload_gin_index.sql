-- V208: 删除 outbox 热写路径上没有读取收益的 payload_json GIN 索引。
--
-- V150/V172 为 Console 按 JSON 内容过滤预留了该索引，但生产查询和当前 Mapper
-- 均只按 tenant/status/event/aggregate 等结构化列过滤。容量画像中父子索引扫描次数
-- 始终为 0，空分区上的子索引仍可膨胀到数十 MiB，并放大每次 outbox INSERT 的
-- WAL、脏页和 vacuum 成本。删除父分区索引会同时删除已挂接的分区子索引；保留
-- event_key、aggregate、publish_status 等实际查询使用的 B-tree 索引。
DROP INDEX IF EXISTS batch.idx_outbox_p_payload_json_gin;

-- 兼容尚未执行 V172 分区切换或从旧快照恢复的数据库。
DROP INDEX IF EXISTS batch.idx_outbox_event_payload_json_gin;
