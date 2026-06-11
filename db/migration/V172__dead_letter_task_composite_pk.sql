-- V172: Citus POC-A — dead_letter_task PK 复合化 (tenant_id, id)
--
-- 目的:实测应用层爆炸半径,产出 docs/analysis/citus-pk-composite-poc-2026-06.md;
-- 这是 citus-introduction-plan §0.5 要求的 PK 复合化 POC 第一张表。
--
-- 背景:
--   batch.dead_letter_task 当前 PK = 单列 id(BIGSERIAL PRIMARY KEY,V7 建表)。
--   Citus distributed table 要求 PK 必须含分片键(tenant_id),故需改为复合 PK。
--   本迁移仅改 batch 主表;archive.dead_letter_task_archive 独立维护
--   自己的 PK(pk_dead_letter_task_archive PRIMARY KEY (id),V140 建立),
--   该表非 Citus distributed 候选,暂不跟进。
--
-- FK 依赖扫描结果:无其他表 FK 引用 dead_letter_task.id,可直接 DROP + ADD。
-- ON CONFLICT 扫描结果:dead_letter_task 无 ON CONFLICT 语句,无 upsert 契约风险。
-- mapper 扫描结果:所有按 id 查询的 5 处语句均已含 tenant_id 前置条件,
--   改造后走复合 PK 前缀索引路径,正确性和测试全绿,无需额外 mapper 改动。
--
-- 参见:docs/analysis/citus-pk-composite-poc-2026-06.md

ALTER TABLE batch.dead_letter_task DROP CONSTRAINT dead_letter_task_pkey;
ALTER TABLE batch.dead_letter_task ADD CONSTRAINT dead_letter_task_pkey
    PRIMARY KEY (tenant_id, id);
