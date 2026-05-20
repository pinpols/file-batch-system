-- =========================================================
-- V142: workflow_run 加 (tenant_id, id) 兜底 UNIQUE
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.3 / Top10 §3.3
--
-- 背景:
--   V5 创建 batch.workflow_run 时 PK 仅为 (id), 跨租户隔离依赖应用层 WHERE。
--   2026-05-21 DAO 审计结论(见同份报告 §workflow_run DAO 审计):
--     用户路径(console-api + orchestrator user-facing) 7/7 都带 tenant_id, 无穿透。
--     2 处 selectByIdAnyTenant / cluster reconciler 是 by-design 旁路。
--   本约束属于"防回归"兜底, 与新增 DAO 时漏带 tenant_id 形成最后一道警戒。
--
-- 设计:
--   - UNIQUE (tenant_id, id) 自然成立 (id 已是 PK 全局唯一, tenant_id 任何值都不会破坏唯一性);
--   - 不替代 PK; 不阻塞已有 by-id 查询;
--   - 对未来 partition-by-tenant 改造也是必备前置(分区表 PK 必须含分区键)。
--
-- 影响:
--   ・空间: 复合 BTREE 索引, n × (8 + 64) bytes 量级, workflow_run 表百万级
--     约 70MB, 可接受;
--   ・写入: 每次 INSERT 增加一个 BTREE 维护, 微秒级开销;
--   ・archive 镜像表同步加约束, 保持 schema 一致(虽然 archive 表理论上不会写新行,
--     防止 ArchiveSchemaDriftCheck 后续启用 column-level 比对时报漂移)。
-- =========================================================

ALTER TABLE batch.workflow_run
    ADD CONSTRAINT uk_workflow_run_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE archive.workflow_run_archive
    ADD CONSTRAINT uk_workflow_run_archive_tenant_id UNIQUE (tenant_id, id);
