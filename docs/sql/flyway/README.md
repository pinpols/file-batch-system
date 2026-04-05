# 平台库 Flyway 迁移（说明）

本目录 **不再存放** `V*.sql` 迁移文件，避免与运行时代码双源漂移。

## 权威位置

平台库（`batch_platform`，`batch` / `quartz` schema）的 Flyway 脚本 **唯一维护目录** 为：

`batch-orchestrator/src/main/resources/db/migration/`

- **运行时**：由 **`batch-orchestrator`**（及按需启用 Flyway 的其他模块）从 **`classpath:db/migration`** 加载并执行。
- **其它 Java 模块**：若需在测试或可执行 jar 中带同套脚本，在各自 `pom.xml` 中通过 Maven `resource` / `testResource` **复制上述 orchestrator 目录** 到 `db/migration`，而不是引用本 `docs/sql/flyway` 路径。
- **Docker**：构建上下文排除整个 `docs/` 时，只要仓库里含 `batch-orchestrator`，仍可通过该模块资源正常打包迁移脚本。

## 变更平台结构时

1. 只在 **`batch-orchestrator/.../db/migration/`** 新增或修改 `V<number>__*.sql`，版本号严格递增、不可复用。
2. 需要给人看的说明可写在 **本 README** 或 `docs/sql/sql-script-usage-scenarios.md`，**不要**再在 `docs/sql/flyway` 下恢复第二套 SQL 副本。

## 另见

- [docs/sql/README.md](../README.md) — `docs/sql` 总索引  
- [sql-script-usage-scenarios.md](../sql-script-usage-scenarios.md) — 各类 SQL 职责边界
