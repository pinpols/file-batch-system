# SQL Script Usage Scenarios

本文档说明仓库中各类 SQL 脚本的职责边界、加载方式和适用场景，帮助在开发、测试、联调和部署时快速选择正确脚本。

## 总览

按“谁来执行 SQL”可以分为 4 大类：

1. Flyway 自动迁移（应用启动时执行）
2. 测试前置脚本（`@Sql` 或测试资源加载）
3. Docker 初始化脚本（容器首次启动执行）
4. 手工种子脚本（系统联调用）

---

## 1) 平台库 Flyway 迁移脚本

- **目录**：`docs/sql/flyway/`
- **典型文件**：`V1__create_schema.sql` 到当前最新平台迁移（例如 `V31__add_batch_day_support.sql`）
- **作用**：
  - 演进平台库 `batch_platform` 结构（`batch`/`quartz` schema 下平台相关表）
  - 作为平台数据库结构的主干变更渠道
- **执行方**：
  - 应用启动时由 Flyway 执行（`spring.flyway.enabled=true`）
  - 集成测试/E2E 也会通过测试资源方式加载同一套迁移脚本
- **适用场景**：
  - 新增表/列/索引、约束
  - 生产级 schema 变更
- **注意事项**：
  - 版本号不可复用，需严格递增
  - 避免把一次性测试数据写进 Flyway 迁移

---

## 2) Quartz 脚本

- **目录**：`docs/sql/quartz/`
- **典型文件**：`Q1__create_quartz_tables_postgres_2_5_2.sql`
- **作用**：
  - 初始化 Quartz 调度元数据表（`QRTZ_*`）
- **执行方**：
  - 通常在环境初始化阶段手工或脚本执行
- **适用场景**：
  - 新环境首次启用 Quartz JDBC JobStore
- **注意事项**：
  - Quartz 表只放在 `quartz` schema，不承载平台业务数据

---

## 3) 系统测试数据脚本

- **目录**：`docs/sql/system-test/`
- **典型文件**：
  - `platform_seed.sql`
  - `platform_edge_cases.sql`
  - `business_seed.sql`
  - `business_edge_cases.sql`
- **作用**：
  - 为系统联调/验收准备可复现测试数据集（基础数据 + 边界数据）
- **执行方**：
  - `scripts/local/load-system-test-data.sh`
  - 或手工 `psql -f ...`
- **适用场景**：
  - 本地联调、演示、回归环境准备
- **注意事项**：
  - 不作为生产迁移手段
  - 关注脚本是否包含 `truncate`/重置行为，避免误跑到非测试库

---

## 4) E2E 业务库 schema 脚本（与 business 单源）

- **权威文件**：`docs/sql/business/V1__create_biz_example_tables.sql`
- **测试 classpath**：`batch-e2e-tests` 的 `pom.xml` 将该文件复制到 `target/test-classes/sql/`，供 `@Sql` 使用（不再维护 `e2e-biz-schema.sql` 副本）
- **作用**：
  - 为 E2E 测试创建 `batch_business.biz` 示例业务表（如导入/导出相关表）
- **执行方**：
  - E2E 测试类通过 `E2eTestSql.BIZ_SCHEMA`（即 `classpath:sql/V1__create_biz_example_tables.sql`）执行
- **适用场景**：
  - 端到端测试需要业务侧真实表结构
- **注意事项**：
  - 该脚本是测试夹具，不是生产 schema 主线

---

## 5) 测试数据 seed 脚本（模块内）

- **目录**：`batch-e2e-tests/src/test/resources/db/testdata/`（平台种子集中维护；`batch-orchestrator` 集成测试经 POM `testResource` 引入 `multi-tenant-seed.sql`）
- **典型文件**：
  - `import-template-config-seed.sql`
  - `export-template-config-seed.sql`
  - `multi-tenant-seed.sql`
- **作用**：
  - 给特定测试用例补齐最小前置数据（模板、worker、租户、路由等）
- **执行方**：
  - 测试类 `@Sql(...)` 明确指定执行
- **适用场景**：
  - 集成测试和 E2E 的场景化数据准备
- **注意事项**：
  - 尽量保持最小数据集，避免跨用例相互污染

---

## 6) 通用测试初始化脚本

- **目录**：`batch-common/src/test/resources/db/`
- **文件**：
  - `platform-init.sql`
  - `business-init.sql`
- **作用**：
  - 作为测试基础设施可复用初始化脚本，快速拉起平台/业务基础表结构
- **适用场景**：
  - 公共测试基建、通用集成测试
- **注意事项**：
  - 不替代 Flyway 迁移主线，避免与正式迁移职责混淆

---

## 7) Docker 初始化脚本

- **目录**：`docker/postgres/init/`
- **文件**：
  - `000-create-business-db.sql`
  - `001-create-schemas.sql`
- **作用**：
  - 本地 Docker Postgres 首次启动时创建数据库和 schema
- **执行方**：
  - Postgres 容器初始化机制自动执行
- **适用场景**：
  - 本地开发环境首次拉起
- **注意事项**：
  - 仅做起步初始化，不承载完整业务迁移历史

---

## 8) 模块内迁移补丁（Orchestrator）

- **目录**：`batch-orchestrator/src/main/resources/db/migration/`
- **典型文件**：
  - `V2__create_worker_registry.sql`
  - `V19__worker_registry_drain.sql`
  - `V24__quota_runtime_state.sql`
  - `V25__worker_registry_current_load.sql`
- **作用**：
  - 模块内部的历史迁移/兼容补丁
- **适用场景**：
  - 维护模块级演进兼容
- **注意事项**：
  - 与 `docs/sql/flyway/` 的主迁移线并存时，需统一治理来源，避免重复定义同一结构

---

## 选型建议（实践规则）

1. **生产结构变更**：优先放 `docs/sql/flyway/`
2. **单个测试场景数据**：放 `src/test/resources/db/testdata/`，由 `@Sql` 加载
3. **E2E 业务表示例**：维护在 `docs/sql/business/`，由 `batch-e2e-tests` 的 `testResource` 打进测试 classpath
4. **本地容器起库**：仅改 `docker/postgres/init/`
5. **系统联调固定数据**：使用 `docs/sql/system-test/`

---

## 常见误用与风险

- 把测试 seed 当成生产迁移：会造成环境不可控
- 把生产迁移写到 Docker init：线上/测试环境无法复用历史版本
- 在多个目录重复维护同一表结构：容易出现 schema 漂移
- 在 Flyway 里写大量场景数据：升级慢且回滚复杂

建议保持“**结构迁移**”与“**场景数据**”分离：  
结构走 Flyway，数据走 testdata/system-test。
