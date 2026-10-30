# 查询业务口径与真实优化证据

## SQL-1 状态分类先定业务口径

MyBatis生命周期分类可以用领域枚举派生值做独立测试对照；SLA纳入、失败作业筛选等特定策略仍保留自己的明确集合，不能为清掉字面量统一传“通用状态参数”。WHERE、部分索引谓词和应用筛选是否同一口径逐项验证。

## SQL-2 读取与索引代价

沿调用频次、N+1、循环逐条查询、分页/排序与租户谓词定位实际扫描放大；聚合/预取要验证空集合、缺数据和结果顺序，不能只减少SQL次数却改业务结果。EXPLAIN和ANALYZE分开，后者会实际执行，写查询和大负载不自动运行。索引去重、分区pruning和类型cast用实际计划证明，不照抄历史候选DDL。

## SQL-3 SQL 与 Java 的职责和原子性

沿 Java 用例、Mapper 参数/默认方法、XML 及真实数据库不变量核对职责：权限、产品策略、状态迁移意图和应用默认值由有明确所有权的 Java 入口提供；CAS、唯一约束、集合领取、计数、JSONB 原子合并和数据库时间保留在 SQL。不得为了简化 XML 改为 Java 先查再整行覆盖。

状态写入与 WHERE/SELECT 分类分别判断；共享生命周期集合对照权威枚举，SLA、dry-run、失败筛选及恢复候选保留专项口径。创建默认值、更新时保留原值的 coalesce、DDL DEFAULT 不混为一谈；动态 SQL 引擎的 builder/validator 也不当作应外置的固定业务 SQL。

参考[SQL/Java 职责审计](../../../../docs/audit/sql-java-responsibility-audit-2026-09-08.md)的竞态和合法保留项，只复用分析方法；旧修复状态和性能数据不继承。

## 当前来源

- [AGENTS.md](../../../../AGENTS.md)
- [database-schema-governance.md](../../../../docs/design/database-schema-governance.md)
- [ADR-031-dual-track-pagination.md](../../../../docs/architecture/adr/ADR-031-dual-track-pagination.md)
- [database-index-review-remediation-2026-10-18.md](../../../../docs/plans/database-index-review-remediation-2026-10-18.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
