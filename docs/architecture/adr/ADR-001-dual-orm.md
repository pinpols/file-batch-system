# ADR-001: 使用 MyBatis + Spring Data JDBC 双 ORM，不引入 JPA

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

批量调度平台需要在多个模块中持久化实体：orchestrator 管理 job_instance / job_partition / job_task 等核心表，worker-core 管理 job_task 的认领与状态变更，两侧都有高并发的 UPDATE 语句（CAS 乐观锁）和动态分页查询。

选型时考虑了三个备选方案：

1. **JPA / Hibernate**：对象关系映射最完整，但代理延迟加载、一级缓存、脏检测机制与 CAS UPDATE 语义冲突；批量 MERGE 场景下 SQL 可预测性差。
2. **纯 MyBatis**：SQL 完全可控，适合复杂查询和 CAS；但简单 CRUD 仍需手写 XML，对轻量管理实体（WorkflowTemplate、JobDefinition）开发效率低。
3. **MyBatis（核心热路径）+ Spring Data JDBC（轻量管理路径）**：按场景选型，SQL 可控性与开发效率兼顾。

## 决策

**采用方案 3**：核心热路径（job_partition、job_task、outbox_event 的认领/状态变更/CAS）使用 MyBatis XML Mapper；低频管理路径（job_definition、workflow_template 等配置类实体）使用 Spring Data JDBC Repository。

具体分工：

| 使用场景 | 技术 | 理由 |
|---|---|---|
| `claimPartition` / `finishTask` / `markPublishing` CAS UPDATE | MyBatis XML | SQL 精确控制，返回影响行数用于乐观锁判断 |
| 动态分页查询（管理后台列表） | MyBatis XML | `<if>` 动态拼接，避免 Criteria API 复杂性 |
| `job_definition` / `workflow_template` CRUD | Spring Data JDBC | 无复杂查询，Repository 接口够用，代码量少 |
| `trigger_request` 插入与状态更新 | MyBatis XML | 需要 `INSERT ... ON CONFLICT DO NOTHING` 精确语义 |

### 模块级使用边界

为避免双 ORM 继续无序扩散，补充以下模块边界：

| 模块 | 允许使用 | 约束 |
|---|---|---|
| `batch-console-api` | `MyBatis` + `Spring Data JDBC` + `JdbcTemplate` 基础设施支撑 | 控制台管理读写允许双 ORM；控制层不直接写 SQL 和业务编排 |
| `batch-orchestrator` | `MyBatis` + `Spring Data JDBC` + `JdbcTemplate` 基础设施支撑 | 编排核心允许双 ORM；CAS / 热路径固定走 MyBatis |
| `batch-trigger` | `MyBatis` + `JdbcTemplate` | 不新增 `Spring Data JDBC` |
| `batch-worker-*` | `MyBatis` + `JdbcTemplate` | 不新增 `Spring Data JDBC`，避免执行链继续叠 ORM |
| `batch-common` | 不承载 ORM 实现 | 只放轻量公共模型、配置和工具，不放运行时重依赖 |

### 依赖治理补充

- `batch-common` 禁止新增以下运行时依赖：对象存储 SDK、OTEL exporter、AI 模型 SDK、Excel 处理库。
- `batch-console-api` 与 `batch-orchestrator` 是当前仅有的双 ORM 模块；其他模块如需新增 `Spring Data JDBC`，必须先修订本 ADR。
- `JdbcTemplate` 仅用于配置支撑、锁表/基础设施或极薄查询支撑，不作为默认业务持久化手段。

## 后果

**正面**：
- CAS UPDATE 的 `affectedRows == 1` 判断在 MyBatis 中天然成立，不受 ORM 缓存干扰。
- 热路径 SQL 在 Mapper XML 中可审计，DBA 可直接 review。
- Spring Data JDBC 的 `CrudRepository` 减少了管理路径的样板代码。

**负面**：
- 项目中存在两套 ORM 风格，新成员需要了解边界划分。
- Spring Data JDBC 不支持懒加载，管理路径如需关联查询仍需手写 MyBatis。
- MyBatis 4.0.0（Spring Boot 4.0 配套版本）与旧版配置方式有差异，需注意 `mybatis-spring-boot-starter` 版本对齐。

## 替代方案拒绝原因

**JPA 拒绝**：Hibernate 的一级缓存会导致 CAS UPDATE 后实体状态与数据库不同步；`@Version` 乐观锁抛出 `OptimisticLockException` 而非返回 0，与现有并发测试（`ConcurrentTaskFinishIntegrationTest`）的断言模式不兼容。
