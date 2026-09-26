---
name: sql-query-performance
description: 用户要求审查或优化 PostgreSQL 查询、Mapper SQL、索引、分页、JSONB 过滤或数据库热点时使用。以真实查询计划和业务负载为依据，避免无证据的全量改写。
---

# SQL 查询性能

## 先找证据

- 确认目标查询的调用路径、租户谓词、排序/分页契约、调用频率、数据规模和延迟目标；优先使用 `pg_stat_statements`、慢查询和生产形态指标定位热点。
- 历史 SQL 审计和 ADR 是背景，不代表当前查询仍存在同一问题。先读当前 Mapper/DDL/调用方，并核对相关迁移和实际索引。
- 用代表性数据和参数检查 `EXPLAIN` 计划。需要执行 `EXPLAIN ANALYZE` 时，只在可控、非生产环境或明确只读安全的查询上运行，并记录 `BUFFERS`、行数估算偏差、实际行数和耗时。

## 选择优化方式

- 分页按访问行为选型：需要跳页或总数的小配置列表可保留 offset；大表深翻页或无限滚动才评估 keyset。keyset 必须有稳定排序及唯一 tie-breaker，并有匹配的复合索引；保持 API 游标、排序和 tenant filter 契约。
- 索引依据谓词、排序、选择性、表规模和写入成本设计。核实多租列顺序、partial predicate、表达式/JSONB 索引是否匹配查询；不要为每个过滤列盲目加索引或全局改写 `SELECT *`。
- 对 `LIKE`/`ILIKE`、JSONB 表达式、聚合和投影收窄，先确认真实使用场景和计划；模糊搜索扩展索引需评估写放大、存储和扩展部署要求。
- SQL 结构或索引变更遵循 `database-migration-safety`：评估锁、构建耗时、兼容发布、回滚和大表影响；不要将在线高风险 DDL 混成未经验证的简单迁移。

## 验收

- 在相同数据库版本、数据规模、参数和缓存条件下对比基线与改动；至少记录执行计划、延迟/吞吐、扫描行数、读写负载和索引体积影响。
- 验证结果集、租户隔离、稳定分页、并发写入期间无漏行/重复，以及执行计划确实选择预期访问路径。
- 没有真实热点或可复现性能收益时，保留简单实现；清楚标记模拟数据、估算计划与生产观测之间的证据差异。

## 参考入口

- `docs/architecture/adr/ADR-031-dual-track-pagination.md`
- `docs/runbook/sql-audit-2026-05-20.md`（历史基线，需复核现状）
- `docs/design/database-schema-guide.md`
- `docs/runbook/distributed-locking-checklist.md`（涉及热点队列索引时）
- `database-migration-safety`、`performance-validation`
