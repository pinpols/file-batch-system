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

- **目录（唯一 SQL 源）**：`batch-orchestrator/src/main/resources/db/migration/`
- **文档入口**：`docs/sql/flyway/README.md`（仅说明，不再存放 `V*.sql` 副本）
- **典型文件**：`V1__create_schema.sql` 起至当前最新版本（例如 `V32__add_batch_day_support.sql`、`V34__create_console_user_account.sql`）
- **作用**：
  - 演进平台库 `batch_platform` 结构（`batch`/`quartz` schema 下平台相关表）
  - Quartz JDBC JobStore（`QRTZ_*`）由 `V2__create_quartz_tables_postgres_2_5_2.sql` 创建
  - 作为平台数据库结构的主干变更渠道
- **执行方**：
  - 应用启动时由 Flyway 执行（`spring.flyway.enabled=true`）；**生产迁库以 `batch-orchestrator` 为准**
  - 其它模块若需 `classpath:db/migration`，在各自 `pom.xml` 中 **复制 orchestrator 上述目录**（见各模块 `build` 配置）
  - 集成测试/E2E 通过 `testResource` 引入同一套脚本
- **适用场景**：
  - 新增表/列/索引、约束
  - 生产级 schema 变更
- **注意事项**：
  - 版本号不可复用，需严格递增
  - 避免把一次性测试数据写进 Flyway 迁移

---

## 2) 系统测试数据脚本

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

## 3) E2E 业务库 schema 脚本（与 business 单源）

- **权威文件**：`docs/sql/business/create_biz_tables.sql`（**非 Flyway**，文件名不带 `V__` 前缀）
- **测试 classpath**：`batch-e2e-tests` 的 `pom.xml` 将该文件复制到 `target/test-classes/sql/`，供 `@Sql` 使用（不再维护 `e2e-biz-schema.sql` 副本）
- **作用**：
  - 为 E2E 测试创建 `batch_business.biz` 示例业务表（如导入/导出相关表）
- **执行方**：
  - E2E 测试类通过 `E2eTestSql.BIZ_SCHEMA`（即 `classpath:sql/create_biz_tables.sql`）执行
- **适用场景**：
  - 端到端测试需要业务侧真实表结构
- **注意事项**：
  - 该脚本是测试夹具，不是生产 schema 主线

---

## 4) 测试数据 seed 脚本（模块内）

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

## 5) 通用测试初始化脚本

- **目录**：`batch-common/src/test/resources/db/`
- **文件**：
  - `platform-init.sql`
  - `business-init.sql`
- **作用**：
  - `platform-init.sql`：仅创建 `batch` / `quartz` schema（与 Flyway `V1__create_schema.sql` 一致），供 Testcontainers 在 Flyway 运行前具备 schema 边界
  - `business-init.sql`：业务库测试初始化（与业务 Flyway/种子策略一致）
- **适用场景**：
  - 公共测试基建、通用集成测试
- **注意事项**：
  - 平台表结构以 Flyway 为唯一权威，勿在 `platform-init.sql` 中维护完整 DDL，避免与迁移分叉

---

## 6) Docker 初始化脚本

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

## 7) 与 §1 的关系（原「双源」说明）

平台迁移 **只维护一份**：`batch-orchestrator/src/main/resources/db/migration/`。不再在 `docs/sql/flyway/` 下保留 SQL 副本，避免与运行时代码漂移。

---

## 选型建议（实践规则）

1. **生产结构变更**：只改 `batch-orchestrator/.../db/migration/`，并遵循 Flyway 版本号规则
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
