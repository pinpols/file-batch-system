# ADR-001: 全平台持久层统一 MyBatis + `JdbcTemplate`（不引入 JPA / Spring Data JDBC）

- **状态**: 已采纳（2026-05-02：**全模块**移除 `spring-boot-starter-data-jdbc` 与 Spring Data JDBC 仓库）
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

批量调度平台需要在多个模块中持久化实体：orchestrator 管理 job_instance / job_partition / job_task 等核心表，worker 管理认领与状态变更，两侧都有高并发的 UPDATE（CAS 乐观锁）和动态分页查询。

选型时考虑了三个备选方案：

1. **JPA / Hibernate** — 代理、`@Version` 异常语义等与现有 CAS / 集成测试断言不一致。
2. **MyBatis（XML Mapper）** — SQL 可控、`<if>` 动态查询、乐观锁影响行数判断清晰。
3. **Spring Data JDBC** — 曾用于少量配置态 `CrudRepository`；**已弃用**，配置态与运行态一律 Mapper，减少双 ORM 与启动期仓库扫描。

## 决策

**全业务模块**（`batch-orchestrator`、`batch-console-api`、`batch-trigger`、`batch-worker-*`）持久化：**`mybatis-spring-boot-starter` + `spring-boot-starter-jdbc`**。**禁止** `spring-boot-starter-data-jdbc`、**禁止** `@EnableJdbcRepositories`。

| 场景 | 技术 | 说明 |
|------|------|------|
| CAS / 热路径 / 复杂 SQL | MyBatis XML | `claimPartition`、`finishTask`、outbox、分页列表等 |
| 配置态表（job_definition、workflow、calendar、quota 等） | MyBatis XML | 与运行态同一套 Mapper + `resultMap` / constructor 映射 |
| 极薄基础设施 | `JdbcTemplate` | 锁表、支撑查询（不作为默认业务持久化） |

### 模块边界

| 模块 | 约束 |
|------|------|
| `batch-orchestrator` / `batch-console-api` | `MyBatis` + `JdbcTemplate`；**禁止** Spring Data JDBC |
| `batch-trigger` / `batch-worker-*` | `MyBatis` + `JdbcTemplate`；**禁止** Spring Data JDBC |
| `batch-common` | 不承载 ORM 实现 |

### 依赖治理

- `batch-common` 禁止新增重运行时依赖（对象存储 SDK、OTEL exporter、AI SDK、Excel 库等）。
- **任何**业务模块 **不得** 引入 `spring-boot-starter-data-jdbc`；`scripts/ci/check-dependency-boundaries.py` 门禁拦截。
- JSONB / 自定义类型通过 **MyBatis `TypeHandler`**（如 `JsonbStringTypeHandler`、`MapJsonbTypeHandler`）映射，不依赖 Spring Data 的 `Converter`。

## 后果

**正面**：单一持久化风格；SQL 可审计；无 SDJ 与 MyBatis 的重复注册与转换器分叉。

**负面**：配置表也要维护 Mapper XML；新成员以 **Mapper 约定** 为主而非 `Repository` 接口。

## 替代方案拒绝原因

**JPA**：见上。**Spring Data JDBC（历史）**：Repository 与 MyBatis 双栈增加心智与 CI 复杂度；配置表已全部有 Mapper 后下线 SDJ。
