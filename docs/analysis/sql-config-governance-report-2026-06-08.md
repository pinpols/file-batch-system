# SQL / 配置混杂治理报告与整改计划

日期：2026-06-08

## 结论

当前项目的主干分层并没有失控：数据库迁移、稳定业务查询、应用配置分别主要落在 Flyway、MyBatis XML 和 YAML 中。但 sim、load-test、ops、local 验证脚本里存在较多 SQL heredoc、`psql -c` 字符串和 JSON 配置内嵌，部分 Java service 也承担了本应属于 mapper/repository 或 SQL resource 的大段清理 SQL。

治理目标不是“项目里不能有 SQL”，而是把 SQL 放回正确边界：

- 迁移 SQL 归 Flyway。
- 稳定 CRUD / 查询 SQL 归 Mapper XML 或 repository。
- 动态 SQL 引擎能力保留在 Java builder / validator / plugin executor 中。
- sim / benchmark / ops SQL 从 shell 中抽出为 `.sql` 文件，shell 只做编排。
- step 参数、fixture 配置从 shell heredoc 中抽出为 JSON / YAML / SQL seed fixture。

## 扫描范围

本轮扫描覆盖：

- `scripts/**/*.sh`
- `load-tests/**/*.sh`
- `batch-*/src/main/java/**/*.java`
- `docs/test-data/**/*.sql`
- `src/main/resources/**/*.xml`
- `src/main/resources/**/*.yml`

初步扫描结果：

| 类型 | 数量 / 现象 | 判断 |
|---|---:|---|
| MyBatis Mapper XML | 132 个 | 正常，是稳定业务 SQL 的主要承载层 |
| Flyway migration SQL | 166 个 | 正常，是 schema 演进的权威入口 |
| 应用 YAML | 31 个 | 正常，配置有集中承载 |
| 含 SQL 信号的 shell | 约 29 个 | 需要分级治理，尤其 sim / local / load-test |
| 含 JDBC / 动态 SQL 信号的 Java | 较多 | 需要区分“SQL 引擎能力”和“service 内联 SQL” |

## 问题分级

### P0：上线前建议治理

#### 1. sim 脚本混合业务 DDL、数据 seed、平台配置和 JSON step_params

代表文件：

- `scripts/sim/10-process-stage4.sh`

典型现象：

- shell heredoc 里创建业务表。
- shell heredoc 里插入 `batch.job_definition` / `batch.pipeline_definition` / `batch.pipeline_step_definition`。
- `step_params` 使用 `jsonb_build_object` 内嵌在 SQL 中。
- 同一脚本同时承担业务 fixture、平台配置、场景编排。

风险：

- 难 review：配置变更隐藏在 shell SQL 中。
- 难复用：其他 sim / e2e / benchmark 不能共享同一份 fixture。
- 难排错：SQL 失败、配置错误、curl 编排错误混在一个脚本里。
- 难防漂移：真实产品配置模型变化后，脚本内 JSON 不容易被发现。

治理建议：

- 拆出 `docs/test-data/sim-stage4-process-fixtures.sql` 或 `scripts/sim/sql/stage4-process-fixtures.sql`。
- 拆出 `docs/test-data/sim-stage4-process-step-params.json`。
- shell 只保留：
  - 参数解析。
  - `psql -v biz_date=... -f xxx.sql`。
  - 启动任务 / 查询状态 / 打印结果。

#### 2. local 验证脚本内联大量 cleanup / seed SQL

代表文件：

- `scripts/local/validate-seed-scenarios.sh`

典型现象：

- `do_cleanup()` 中多处直接拼接 `DELETE FROM ...`。
- 以 shell 变量拼接 `LIKE '$pattern'`、`IN ($probe_instances)`。
- 同一脚本中还包含 seed job、seed workflow、seed process pipeline、seed cron job。

风险：

- 误删半径不够显式，cleanup 逻辑不易独立 review。
- shell 变量进入 SQL 字符串，虽然当前变量来源可控，但长期维护风险高。
- 失败重跑时难判断是验证逻辑失败还是清理 SQL 残留。

治理建议：

- 拆出：
  - `scripts/local/sql/validate-seed-cleanup.sql`
  - `scripts/local/sql/validate-seed-fixtures.sql`
  - `scripts/local/sql/validate-seed-cron-fixture.sql`
- 使用 `psql -v pattern=... -v probe_instances=... -f xxx.sql`。
- SQL 文件内统一使用 `:'var'` / `:var`，并加 `ON_ERROR_STOP=1`。
- cleanup SQL 尽量按 tenant / job_code / run_id 锁定范围。

#### 3. 控制面 benchmark 脚本内联大段 cascade cleanup SQL

代表文件：

- `load-tests/scripts/run-control-plane-worker-benchmark.sh`

典型现象：

- `cleanup_atomic_trigger()` 中存在大段 `WITH` + `DELETE` cleanup。
- benchmark 编排与数据清理强绑定。

风险：

- 压测场景扩展时容易复制粘贴同一套 cleanup SQL。
- cleanup 顺序和表依赖变更时，多个脚本需要同步改。
- 不利于独立 dry-run / explain / DBA review。

治理建议：

- 拆出 `load-tests/sql/cleanup-control-plane-worker.sql`。
- benchmark shell 只调用 `psql -v run_prefix=... -f cleanup-control-plane-worker.sql`。
- cleanup SQL 增加注释：清理范围、依赖顺序、允许影响的表。

#### 4. Console 测试数据清理 service 内联大量 SQL

代表文件：

- `batch-console-api/src/main/java/com/example/batch/console/domain/ops/service/ConsoleAdminTestDataCleanupService.java`

典型现象：

- service 中维护大量 `DELETE FROM batch.xxx` 字符串。
- 同时处理 job、workflow、pipeline、file、tenant、console user 等多个上下文。

风险：

- service 层职责过重，SQL 依赖顺序和业务规则混在一起。
- 表结构变化时，编译不一定能暴露 SQL 漂移。
- 复用性差，其他测试 / ops 无法复用同一套清理逻辑。

治理建议：

- 短期：下沉到 `ConsoleAdminTestDataCleanupRepository`，service 只编排清理动作。
- 中期：迁移到 MyBatis XML mapper 或 SQL resource。
- 每个 cleanup 动作加单测 / 集成测试覆盖清理范围。

### P1：排期治理

#### 5. ops heal / inspect 脚本中的内联 SQL

代表文件：

- `scripts/ops/heal-dead-letters.sh`
- `scripts/ops/heal-drain-timeout.sh`
- `scripts/ops/heal-retry-partitions.sh`
- `scripts/ops/heal-retry-tasks.sh`
- `scripts/ops/heal-stuck-outbox.sh`
- `scripts/ops/heal-zombie-pipelines.sh`
- `scripts/ops/inspect-db.sh`
- `scripts/ops/inspect-workers.sh`

判断：

ops 脚本允许包含数据库操作，但生产运维 SQL 应该更容易审计和复用。

治理建议：

- 建立 `scripts/ops/sql/`。
- 每个 heal/inspect 动作对应一个 SQL 文件。
- shell 只负责校验参数、打印确认、调用 SQL、输出影响行数。
- 危险写操作默认要求显式 `--execute`，无该参数只 dry-run。

#### 6. sim / load-test 中配置与数据耦合

代表文件：

- `scripts/sim/09-export-stage3.sh`
- `scripts/sim/11-import-stage2b.sh`
- `scripts/sim/12-export-stage3b.sh`
- `load-tests/scripts/prepare-worker-load-data.sh`
- `load-tests/scripts/cleanup-worker-load-data.sh`

治理建议：

- fixture SQL 统一放到 `docs/test-data` 或 `load-tests/sql`。
- 每个 sim stage 建立明确命名：
  - `*-source.sql`
  - `*-fixtures.sql`
  - `*-cleanup.sql`
- step_params 配置单独放 JSON / YAML，避免 JSON-in-SQL-in-shell。

### P2：规范和门禁

#### 7. 缺少边界规则和 CI 检查

现状：

项目有代码规范文档，但 SQL / 配置放置边界还没有形成可执行规则。

治理建议：

- 在 `docs/coding-conventions.md` 增加“SQL 与配置放置规则”。
- 新增轻量 CI 扫描：
  - 禁止新增大段 `<<SQL` heredoc，白名单除外。
  - 禁止 shell 中新增 `jsonb_build_object`，白名单除外。
  - 禁止 service 层新增大量 `jdbc.update("DELETE ...")`，白名单除外。
  - 允许 `scripts/**/sql/*.sql`、`load-tests/sql/*.sql`、Flyway、Mapper XML。

## Java SQL 边界判断

### 可以保留在 Java 的 SQL

以下属于平台能力本身，不建议为了“去 SQL 化”强行搬到 shell 或 XML：

- `batch-worker-import/.../GenericJdbcMappedImportLoadPlugin.java`
- `batch-worker-export/.../SqlTemplateExportDataPlugin.java`
- `batch-worker-process/.../SqlTransformComputePlugin.java`
- atomic SQL / stored procedure executor
- data quality / dry-run / DB sensor 中需要执行用户配置 SQL 的 executor

原因：

- import / export / process / atomic 本来就是动态 SQL 执行引擎。
- SQL 需要根据模板、字段映射、分片、COPY/UPSERT 策略动态生成。
- 正确治理方式是集中 builder / validator / test，而不是迁移到 shell。

要求：

- identifier 必须校验或引用。
- 值必须走 bind 参数。
- 动态 SQL builder 独立成类，避免散落在业务流程中。
- 每类 SQL 模板至少覆盖：
  - 正常路径。
  - 非法 identifier。
  - 空字段 / 重复字段。
  - 幂等重跑。
  - 大字段 / JSONB / 时间字段。

### 不建议保留在 Java service 的 SQL

以下类型应逐步迁走：

- 大段 cleanup SQL。
- 跨多个表的手写 cascade delete。
- 与测试数据 / fixture 强相关的 SQL。
- 固定 CRUD 查询。

目标位置：

- Mapper XML。
- Repository。
- SQL resource。
- 测试 fixture `.sql`。

## 目标目录规范

建议最终形成以下结构：

```text
db/migration/
  Vxxx__*.sql                         # schema / migration 权威入口

batch-*/src/main/resources/mapper/
  *.xml                               # 稳定业务查询 / CRUD

docs/test-data/
  sim-*-source.sql                    # sim 源数据
  sim-*-fixtures.sql                  # sim 平台配置 / fixture
  sim-*-cleanup.sql                   # sim 清理逻辑
  *.json                              # step_params / 文件模板样例

load-tests/sql/
  prepare-*.sql
  cleanup-*.sql
  report-*.sql

scripts/ops/sql/
  inspect-*.sql
  heal-*.sql

scripts/**/*.sh
  # 只做参数、编排、调用 psql/curl、日志输出
```

## 整改计划

### 阶段 1：无行为变化抽取

目标：不改业务逻辑，只把 shell 内联 SQL 抽出来。

范围：

1. `scripts/sim/10-process-stage4.sh`
2. `load-tests/scripts/run-control-plane-worker-benchmark.sh`
3. `scripts/local/validate-seed-scenarios.sh`

动作：

- 新增对应 `.sql` 文件。
- shell 通过 `psql -v ... -f xxx.sql` 调用。
- 保留原有变量名和执行顺序。
- 每个 SQL 文件头部写明：
  - 用途。
  - 清理/写入范围。
  - 需要的 psql 变量。
  - 是否允许生产环境执行。

验收：

- `bash -n` 通过。
- 相关 sim / benchmark / validate 脚本能跑通原场景。
- `git diff` 中 shell 行数明显下降。
- 不改变插入的数据内容和任务行为。

### 阶段 2：Java cleanup SQL 下沉

目标：降低 service 层 SQL 密度。

范围：

1. `ConsoleAdminTestDataCleanupService`

动作：

- 新增 repository 或 mapper。
- 将 delete SQL 按上下文拆分：
  - job cleanup
  - workflow cleanup
  - pipeline cleanup
  - file cleanup
  - tenant cleanup
  - console user cleanup
- service 只负责参数校验、调用顺序和结果汇总。

验收：

- 原 Console admin cleanup 单测 / 集成测试通过。
- 新增 repository / mapper 层测试。
- cleanup 影响行数与改造前一致。

### 阶段 3：动态 SQL 引擎硬化

目标：保留 Java 动态 SQL 能力，但减少不可控拼接。

范围：

1. import generic JDBC load plugin
2. export SQL template plugin
3. process SQL transform plugin
4. atomic SQL / stored procedure executor

动作：

- 梳理每个 plugin 的 SQL builder。
- 将重复 SQL 拼装逻辑提取到专门 builder。
- 补 identifier validator 测试。
- 补非法配置拒绝测试。

验收：

- 单测覆盖非法表名、非法列名、空映射、重复映射。
- 运行已有 import / export / process / atomic 集成测试。
- 不引入性能退化。

### 阶段 4：规范和 CI 门禁

目标：防止问题回流。

动作：

- 更新 `docs/coding-conventions.md`。
- 新增脚本扫描规则，例如：
  - shell 中新增 `<<SQL` 需要白名单。
  - shell 中新增 `jsonb_build_object` 需要白名单。
  - service 层新增 `jdbc.update("DELETE` 需要白名单。
- 白名单明确列出：
  - Flyway migration。
  - Mapper XML。
  - `load-tests/sql`。
  - `scripts/ops/sql`。
  - 动态 SQL plugin builder。

验收：

- CI 能在 PR 阶段提示新增混杂点。
- 文档里说明如何申请白名单。
- 新增 sim / benchmark 场景默认走 `.sql` / `.json` fixture。

## 建议执行顺序

| 顺序 | 项目 | 优先级 | 风险 | 收益 |
|---:|---|---|---|---|
| 1 | 抽取 `scripts/sim/10-process-stage4.sh` | P0 | 低 | 高 |
| 2 | 抽取 `load-tests/scripts/run-control-plane-worker-benchmark.sh` cleanup SQL | P0 | 低 | 高 |
| 3 | 抽取 `scripts/local/validate-seed-scenarios.sh` cleanup / seed SQL | P0 | 中 | 高 |
| 4 | 下沉 `ConsoleAdminTestDataCleanupService` SQL | P0/P1 | 中 | 中 |
| 5 | 建立 `scripts/ops/sql` | P1 | 低 | 中 |
| 6 | 动态 SQL plugin builder / validator 硬化 | P1 | 中 | 中 |
| 7 | CI guardrail | P2 | 低 | 高 |

## 上线判断

这不是阻断上线的功能性 bug，但属于工程治理和生产可维护性问题。建议上线前至少完成 P0 中的 shell SQL 抽取，避免上线验收、压测、sim 复验继续依赖难审计的大型 shell heredoc。

Java 动态 SQL 引擎不建议在上线前大重构；当前更稳妥的做法是先补测试和边界校验，后续再做 builder 结构优化。

## 落地记录

2026-06-08 已落地：

- `scripts/sim/10-process-stage4.sh` 的业务 fixture 与平台 fixture 已抽到 `docs/test-data/sim-stage4-process-*.sql`。
- `load-tests/scripts/run-control-plane-worker-benchmark.sh` 的控制面 cleanup 已抽到 `load-tests/sql/cleanup-control-plane-worker.sql`。
- `scripts/local/validate-seed-scenarios.sh` 的 sweep cleanup 已抽到 `scripts/local/sql/validate-seed-cleanup-*.sql`。
- `scripts/local/validate-seed-scenarios.sh` 的 STRICT process seed 已抽到 `scripts/local/sql/validate-seed-process-fixture.sql`。
- `ConsoleAdminTestDataCleanupService` 已拆成薄 service + `ConsoleAdminTestDataCleanupRepository`，事务边界保留在 service。
- `docs/coding-conventions.md` 已补 SQL / 配置放置边界。
- `scripts/ci/check-sql-config-boundaries.sh` 已接入 `pr-gate` 和 `full-ci-gate`，用于阻止新 shell 文件继续引入 SQL / 配置混杂。

追加落地：

- `scripts/ops/heal-dead-letters.sh` 的 summary / count / batch 查询已抽到 `scripts/ops/sql/heal-dead-letters-*.sql`。
- `scripts/ops/heal-retry-tasks.sh`、`scripts/ops/heal-retry-partitions.sh` 的 count / batch 查询已抽到 `scripts/ops/sql/heal-retry-*.sql`。
- `scripts/ops/heal-drain-timeout.sh` 的 DRAINING 超时 worker 查询已抽到 `scripts/ops/sql/heal-drain-timeout-workers.sql`。
- `scripts/ops/heal-stuck-outbox.sh` 的 count / breakdown / reset / notify 已抽到 `scripts/ops/sql/heal-stuck-outbox-*.sql`。
- `scripts/ops/heal-zombie-pipelines.sh` 的 list / count / update 已抽到 `scripts/ops/sql/heal-zombie-pipelines-*.sql`，同时移除了 `eval $PSQL -c` 调用。
- `scripts/ops/inspect-workers.sh` 的 worker 巡检查询已抽到 `scripts/ops/sql/inspect-workers-*.sql`。
- `scripts/ops/inspect-db.sh` 的 Flyway、alert、stuck job、outbox、DLQ、retry、terminal consistency 查询已抽到 `scripts/ops/sql/inspect-db-*.sql`。
- `load-tests/scripts/prepare-worker-load-data.sh` 的业务 seed、process pipeline JSONB 配置、dispatch file 记录写入已抽到 `load-tests/sql/prepare-worker-load-*.sql`。
- `load-tests/scripts/cleanup-worker-load-data.sh` 的平台 / 业务 cleanup 已抽到 `load-tests/sql/cleanup-worker-load-*.sql`。
- `scripts/dev/trigger-process-demo.sh` 的 demo 建表、pipeline 配置、结果查询已抽到 `scripts/dev/sql/trigger-process-demo-*.sql`。
- `scripts/sim/09-export-stage3.sh` 的 export source seed 已抽到 `docs/test-data/sim-stage3-export-source.sql`。
- `scripts/sim-4day/00-clean.sh`、`42-run-4days-batchday.sh`、`50-watch.sh` 的清理、日切和仪表盘查询已抽到 `scripts/sim-4day/sql/*.sql`。
- `scripts/data/load-system-test-data.sh` 的 seed 后序列同步和 runtime 收尾 SQL 已抽到 `scripts/data/sql/*.sql`。
- `scripts/local/apply-pending-flyway-migrations.sh` 的 Flyway history 查询 / 记账 SQL 已抽到 `scripts/local/sql/select-applied-flyway-versions.sql` 和 `scripts/local/sql/insert-flyway-history.sql`。
- `scripts/local/replay-forensic-bundle.sh` 的取证 sha 校验、临时 schema、snapshot 装载、触发对查询和 replay snapshot 查询已抽到 `scripts/local/sql/*forensic*.sql`。
- `scripts/sim/03-import-tenants.sh` 的导入后验证查询已抽到 `docs/test-data/sim-import-tenants-verify.sql`。
- `scripts/dev/sonar-scan.sh` 的 H2 reset 语句已抽到 `scripts/dev/sql/sonar-reset-password-flag.sql`。

保留说明：

- ops shell 中仍保留少量 dry-run 文案，例如打印将执行的 `UPDATE` 摘要，方便值班人员确认动作。这些不是实际 SQL 执行入口。
- CI 白名单仍允许既有 ops shell 出现 SQL 关键词，避免注释和 dry-run 文案误报；实际新增 SQL 应放到 `scripts/ops/sql/*.sql`。
- `scripts/local/validate-seed-scenarios.sh` 仍保留多处一行式读探针 SQL。它们不是大段 fixture / 配置 / DDL，但严格按“所有 SQL 都离开 shell”口径仍需继续拆成 `scripts/local/sql/validate-seed-*.sql`，建议独立治理，避免一次性生成几十个探针文件影响可读性。
