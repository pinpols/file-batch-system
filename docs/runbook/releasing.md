# Release Flow / 版本发布流程

> 适用：本仓库 `batch-platform` 根 Maven reactor。平台运行时固定 10 个逻辑模块（batch-common / batch-trigger / batch-orchestrator / batch-worker-{core,import,export,process,dispatch,atomic} / batch-console-api），Java SDK 三件套位于 `sdk/java/{core,spring,testkit}` 并纳入根 reactor。共享配置基线 `batch-defaults.yml` 位于 `batch-common/src/main/resources/`,详见 ADR-029 修订版。
>
> 维护规则：后端版本变更统一使用 `bash scripts/ci/bump-version.sh <version>`，再运行后端 `scripts/ci/check-version-alignment.sh` 和前端 `npm run check:version -- <version>`，通过后才能打 tag。版本同步行为以该脚本为准，不手工用 `sed` 修改版本落点。根 `pom.xml` 的 `<revision>` 是 Maven 版本入口；不要假定当前开发版本一定带 `-SNAPSHOT`，以工作树的 pom、根 `CHANGELOG.md` 和版本检查脚本输出为准。

## 0. 运行时硬性前提

| 组件 | 最低版本 | 备注 |
|---|---|---|
| **PostgreSQL** | **≥ 11** | V100+ 多个 Flyway 迁移依赖 PG 11 的"`ADD COLUMN ... DEFAULT 常量`不重写行"优化；< 11 会触发全表 rewrite + 长时间 AccessExclusiveLock |
| **Kafka** | ≥ 3.5（推荐 4.x） | KRaft / ZK 都兼容；ADR-010 trigger outbox topic 需要支持 idempotent producer |
| **Redis** | ≥ 6.2 | quota Lua / ShedLock / cache pub-sub 用到的命令均在此版本可用 |
| **JDK** | 21 | 见根 pom `<java.version>` / `<maven.compiler.release>` |

## 0.1 V124 上线前置检查（partial unique 改造）

V124 把 4 张表的 UNIQUE 改为 partial unique index（消除 PG NULL ≠ NULL bypass）。
**若存量数据已经存在重复 NULL 行，迁移会失败**。上线前必须用以下 SQL 在生产灰度库扫一遍，
有结果就先清理（业务侧填充 idempotency_key / checksum_value 或物理删冗余行）再跑迁移：

```sql
-- job_partition：tenant_id + idempotency_key 是否有重复（仅非 NULL 行）
SELECT tenant_id, idempotency_key, COUNT(*)
FROM batch.job_partition WHERE idempotency_key IS NOT NULL
GROUP BY 1,2 HAVING COUNT(*) > 1;

-- file_record：含 checksum 的行是否有重复 path
SELECT tenant_id, checksum_value, storage_path, COUNT(*)
FROM batch.file_record WHERE checksum_value IS NOT NULL
GROUP BY 1,2,3 HAVING COUNT(*) > 1;

-- job_task：有 partition_id 时 (partition, seq) 是否唯一
SELECT job_partition_id, task_seq, COUNT(*)
FROM batch.job_task WHERE job_partition_id IS NOT NULL
GROUP BY 1,2 HAVING COUNT(*) > 1;

-- pipeline_instance：related_job_instance_id 是否唯一
SELECT related_job_instance_id, COUNT(*)
FROM batch.pipeline_instance WHERE related_job_instance_id IS NOT NULL
GROUP BY 1 HAVING COUNT(*) > 1;

-- workflow_run：同 (tenant, def, biz_date) 是否有多个非终态 run
SELECT tenant_id, workflow_definition_id, biz_date, COUNT(*)
FROM batch.workflow_run WHERE run_status IN ('CREATED','RUNNING')
GROUP BY 1,2,3 HAVING COUNT(*) > 1;
```

V124 的 CHECK / FK 约束用 `NOT VALID` 模式，不扫描存量，但运维窗口期内需要手动 VALIDATE：

```sql
ALTER TABLE batch.batch_day_replay_session VALIDATE CONSTRAINT ck_replay_session_result_policy;
ALTER TABLE batch.batch_day_replay_session VALIDATE CONSTRAINT ck_replay_session_config_version_policy;
ALTER TABLE batch.result_version VALIDATE CONSTRAINT ck_result_version_dq_gate_status;
ALTER TABLE archive.result_version_archive VALIDATE CONSTRAINT ck_result_version_archive_dq_gate_status;
ALTER TABLE batch.data_quality_check VALIDATE CONSTRAINT fk_dq_check_rule_id;
ALTER TABLE batch.calendar_holiday VALIDATE CONSTRAINT ck_calendar_holiday_group_code_required;
```

## 0.2 V119 历史注释（rolling deploy 已过期）

V119 把 `job_execution_log` / `job_step_instance` 的 FK 改为 `ON DELETE CASCADE`，并同 commit 调整了
`DefaultJobOpsService.deleteJobPartitionsByInstanceIds` 的删除顺序。schema 迁移和代码部署严格同时上线
（标准 rolling deploy 单一 commit），上线后回退**只回退代码 + 保留 schema** 是安全的（CASCADE 关系新代码用、
旧代码不依赖）。该问题在 V119 上线时已经过窗口，**仅作历史记录**，无需修复。

## 1. 版本号规范（SemVer 2.0.0）

格式：`MAJOR.MINOR.PATCH[-PRERELEASE]`

| 形态 | 示例 | 语义 |
|---|---|---|
| `MAJOR` | `2.0.0` | 不向后兼容的破坏性改动（API 删字段 / 行为反向 / DB schema 不可逆） |
| `MINOR` | `1.1.0` | 向后兼容的新功能（加字段 / 加端点 / 加 ADR backend） |
| `PATCH` | `1.0.1` | 向后兼容的 bug fix（不加新功能） |
| `-SNAPSHOT` | `X.Y.Z-SNAPSHOT` | 经批准开启预发布周期时使用的开发版本示例；不是当前默认版本 |
| `-RC.N` | `1.1.0-RC.1` | 准发布候选（QA / 灰度验证用） |
| `-M1` / `-alpha.1` / `-beta.1` | `1.1.0-M1` | 早期里程碑 / 内部预览（可选，本项目暂不强制） |

### 1.1 前后端联合版本约定

`file-batch-system` 与 `batch-console` 作为同一平台发布，正式版本和 RC 使用相同的
`MAJOR.MINOR.PATCH[-PRERELEASE]`。后端发布计划是联合版本号的权威入口，实际部署仍由
Release Manifest 中固定的前后端 commit SHA 和镜像 digest 唯一标识。

- 普通功能、修复、测试、文档和 CI PR **不单独递增版本号**。有发布影响的 PR 只把条目累计到各仓库的 `[Unreleased]`。
- 只有准备一次联合发布时，才分别创建前端和后端 Release PR，并在两个仓库中同步一次目标版本。
- 目标版本取本发布周期全部变更中的最高级别：只有兼容修复时递增 `PATCH`；存在向后兼容的新功能时递增 `MINOR`；存在破坏性契约或行为变化时递增 `MAJOR`。
- 即使只有一个仓库有业务改动，两个仓库仍使用同一目标版本。无业务变化的一方只做版本对齐，并在 Release Notes 中明确说明。
- RC 的功能范围在 `RC.1` 前冻结。RC 后只允许发布阻断问题修复；需要重新验收时递增 `RC.N`，普通修复 PR 本身不修改版本号。
- 已发布 tag 不移动、不覆盖、不复用。前后端任一 tag 或 Release 创建失败时，该联合发布保持未完成状态，修复发布动作后补齐另一侧，不重打已经发布的 tag。

联合版本示例：

| 发布周期内最高变更 | 两仓目标版本 |
|---|---|
| 前端 `fix`，后端无业务变化 | `1.0.1` |
| 前端 `fix`，后端新增兼容 API | `1.1.0` |
| 任一仓库包含破坏性变化 | `2.0.0` |
| `1.1.0-RC.1` 验收后仍需修复 | `1.1.0-RC.2` |

版本号表达对外兼容语义，不替代制品身份。每次联合发布记录至少包含目标版本、前端 SHA、
后端 SHA、前端镜像 digest 和后端镜像集合；生产晋级与回滚都使用这组不可变信息，不能仅依赖
`latest` 或可移动的环境状态。

平台不采用 CalVer 或 Spring Cloud 式 BOM Release Train：后端 reactor 仍由 `${revision}` 统一，
前后端通过相同 SemVer 和 Release Manifest 组成一次联合发布，不引入额外的日期版本或 BOM 版本。

## 2. 标准发布 flow（main 分支）

### 2.1 平时（开发期）

```bash
# 使用当前根 pom.xml 中的 <revision> 构建；不要在普通 PR 中单独改版本。
mvn package -DskipTests
mvn -pl batch-orchestrator -am test
```

PR 合并到 main 不改版本号；各 PR 只维护 `[Unreleased]`。只有明确创建联合 Release PR 或开启新的预发布周期时，才按版本策略更新版本；正式版本分支保持正式版本号。

### 2.2 准备联合 Release PR

确认两个仓库当前版本、`[Unreleased]`、最近 GA tag 和待发布 SHA 后，按 §1.1 的最高变更级别确定联合发布号 `X.Y.Z`。前后端 Release PR 必须使用同一版本；不得仅凭单仓 Conventional Commits 输出决定联合版本。若从预发布版本发布，使用以下统一版本工具：

```bash
# 同步后端版本落点；GA 版本会更新 Chart / prod image tag 并补正式 Changelog 小节。
bash scripts/ci/bump-version.sh X.Y.Z

# 2) 验证
scripts/ci/check-version-alignment.sh
mvn -DskipTests clean package
mvn test

# 提交后端 Release PR；前端同时准备同版本 Release PR
git add pom.xml load-tests/pom.xml helm docs CHANGELOG.md scripts
git commit -m "release: X.Y.Z"
```

两个 Release PR 合并后，记录其 `main` commit SHA，并再次确认前后端版本相同。此时只形成待发布
源码基线，不立即打 tag。使用 `compose-cd` 的 `frontend_sha` 输入固定前端提交；后端提交由 workflow
运行自身的 commit 固定。生成的 Release Manifest 必须记录这两个 SHA 和不可变镜像 digest。

将该 Release Manifest 部署到 staging，完成后端门禁、真实前后端 E2E、前端可访问性与 Lighthouse、
升级和回滚检查。只有同一组 SHA/digest 的发布候选通过验收，才能在两个仓库对应 commit 上创建同名
版本 tag，并分别生成 GitHub Release；后端 tag 继续遵循 §6 的 annotated tag 要求：

```bash
git tag -a vX.Y.Z -m "Release X.Y.Z — <一句话亮点>"
git push origin vX.Y.Z
```

Release Notes 至少记录目标版本、前端 SHA、后端 SHA 和制品 digest。创建 tag 或 Release 不等于生产
部署；生产仍从已验收 Release Manifest 晋级。

### 2.3 开启下一开发版本（按需，不自动执行）

正式版本发布后，主干默认保持正式版本号。只有版本规划明确开始新的预发布周期时，才运行版本工具设置下一开发版本；不需要为每次合并自动切成 `SNAPSHOT`。

```bash
# 只有已批准开启下一预发布周期时执行；版本号由版本计划决定。
bash scripts/ci/bump-version.sh X.Y.Z-SNAPSHOT
bash scripts/ci/check-version-alignment.sh
```

### 2.4 当前自动化边界

- 后端 `bump-version.sh` 自动同步本仓库版本落点，`check-version-alignment.sh` 负责阻止本仓库内部漂移。
- 前端 release-please 自动整理 Release PR、Changelog、tag 和 GitHub Release，但它不是联合版本号的权威来源。
- `compose-cd` 自动固定前后端 SHA、构建不可变镜像、生成 Release Manifest，并可部署和验收 staging。
- 当前尚未有一个 workflow 自动创建两仓 Release PR 或自动核对两仓目标版本，因此发布负责人必须在合并前人工确认版本一致，并把实际 SHA/digest 写入 Release Notes。
- 普通 PR 不触发版本递增。未来增加跨仓编排时，也只能创建或更新 Release PR；tag、GitHub Release 与生产晋级必须等待 staging 成功和受保护环境批准，不得因普通 PR 合并自动发布。

后续跨仓自动化应以一个显式输入的 `release_version` 为唯一目标值，并至少执行：SemVer 单调递增检查、
两仓版本一致性、后端 OpenAPI 与前端生成类型漂移检查、双仓 Release PR 创建/更新、Release Manifest
与验收结果关联。自动化不得推断并静默升级 `MAJOR`，也不得移动已存在 tag。

## 3. Patch 发布 flow（hotfix）

从当前生产 GA tag 建 hotfix 分支，版本号使用实际 hotfix 计划，不照抄下例：

```bash
# 1) 从实际生产 GA tag 拉 hotfix 分支
git switch -c hotfix/X.Y.Z vX.Y.P

# 2) 按仓库策略设置 hotfix 预发布版本
bash scripts/ci/bump-version.sh X.Y.Z-SNAPSHOT
git add pom.xml load-tests/pom.xml docs/api docs/sdk CHANGELOG.md
git commit -m "chore: prepare hotfix X.Y.Z"

# 3) cherry-pick 必要的 fix commit（或在该分支直接修）
git cherry-pick <fix-sha>

# 4) 修复验收后设置 GA 版本、检查对齐并按 §2.2 的联合流程发布
bash scripts/ci/bump-version.sh X.Y.Z
bash scripts/ci/check-version-alignment.sh

# 5) 把 hotfix 反向合回 main（避免 main 漏掉 fix）
git checkout main
git merge hotfix/X.Y.Z --no-ff
# 或 cherry-pick 单 fix commit 到 main（不连版本号）
```

## 4. RC / 预览版本 flow

按批准的发布计划确定 RC 版本号（以下 `X.Y.Z-RC.1` 为格式示例）：

```bash
bash scripts/ci/bump-version.sh X.Y.Z-RC.1
bash scripts/ci/check-version-alignment.sh
git commit -am "release: X.Y.Z-RC.1"

# 前端准备同版本 RC；两仓 Release PR 合并并完成联合 staging 验收后再分别打同名 tag。
git tag -a vX.Y.Z-RC.1 -m "Release Candidate 1 for X.Y.Z"
mvn -DskipTests deploy   # 发到 nexus 让 QA 拉
git push origin vX.Y.Z-RC.1

# RC 验证通过后，按 §2.2 设置对应 GA 版本；若需继续修复，则设置下一 RC/SNAPSHOT 版本。
bash scripts/ci/bump-version.sh X.Y.Z
bash scripts/ci/check-version-alignment.sh
```

## 4.5. release-bump-checklist（版本落点与自动同步范围）

后端版本落点由 `scripts/ci/bump-version.sh` 集中同步并由版本对齐门禁校验；不要逐个手工编辑：

| 文件 | 字段 | 语义 | release 时何时改 |
|---|---|---|---|
| `pom.xml` `<revision>` | 根 reactor 单点 | 当前构建 / release 版本 | `bump-version.sh` |
| `load-tests/pom.xml` `<version>` | 独立 Maven 构建 | 与后端版本对齐 | `bump-version.sh` + 对齐门禁 |
| OpenAPI、SDK quickstart | 对外 API / SDK 文档版本 | 与后端版本对齐 | `bump-version.sh` + 对齐门禁 |
| `helm/batch-platform/Chart.yaml` `appVersion` | Chart 默认应用版本 | GA 时同步至新发布版本；预发布版本不改 | `bump-version.sh` |
| `helm/values-prod.yaml` `image.tag` | 生产 values 默认镜像 tag | GA 时由版本工具同步；实际部署时点仍由发布流程控制 | `bump-version.sh` |
| `../batch-console/package.json` `version` | 前端应用版本 | **= 本次后端发布版本** | 前端仓库单独的 release PR 同步修改 |
| `../batch-console/package-lock.json` root `version` | 前端锁文件根版本 | **= package.json version** | 与前端 `npm run check:version` 一起校验 |

前端仓库不纳入本仓库的 Maven reactor，需在配对仓库单独更新版本并检查：

```bash
# backend
bash scripts/ci/check-version-alignment.sh

# frontend, <version> 必须与 backend pom.xml <revision> 相同
cd ../batch-console
npm run check:version -- <version>
```

前端 `check:version` 同时校验 `package.json` 与 `package-lock.json` 的根版本；传入期望版本时还会校验它与发布号一致。任何一个仓库未通过，都不得创建或移动 `v<version>` 发布 tag。

GA 版本工具会同步 `Chart.yaml` 的 `appVersion` 和 `helm/values-prod.yaml` 的默认 `image.tag`；这只更新部署声明，不会自动触发生产部署。前端版本仍由前端仓库独立维护，并通过上述命令检查一致性。

## 5. Maven 命令速查

| 场景 | 命令 |
|---|---|
| 默认 build（用 pom 中 `<revision>`） | `mvn package -DskipTests` |
| 临时覆盖 revision（不改 pom） | `mvn -Drevision=1.0.5 package` |
| 发到 nexus / artifactory | `mvn -Drevision=X.Y.Z deploy` |
| 干跑 IT（使用根 pom 当前 revision） | `mvn -pl batch-orchestrator -am test` |

`flatten-maven-plugin` 在 `install` / `deploy` 期会展开 `${revision}` 为字面量写入 pom，下游消费者拿到的是已展开的版本号 —— 不要绕过此插件。

## 6. Git tag 规范

- 格式 `v<version>`：`v1.0.0` / `v1.1.0-RC.1` / `v2.0.0`
- 必须是 **annotated tag** (`git tag -a`)，不要轻量 tag
- tag 信息至少含：版本号 + 一句话亮点 + 关键变更引用（ADR / migration / commit）
- **仅保留版本 tag**，不打描述性 tag（避免一份 commit 两个名字造成混乱）

## 7. CHANGELOG.md 维护

每个有发布影响的 PR（功能、外部契约、配置默认值、部署/迁移、安全修复、重要缺陷或可验证的性能变化）合入 `main` 时，往 `## [Unreleased]` 对应分类追加一行。纯重构、补测试和不改变约束的文档修正不写入：

```markdown
## [Unreleased]

### Added
- ADR-026 dry-run 全链路落地（V115/V117 + DryRunGuard SPI + L1/L2/L3 service）

### Changed
- ...

### Fixed
- ...
```

Release 时把 `[Unreleased]` 改成 `[X.Y.Z] - YYYY-MM-DD`，再开新空 `[Unreleased]` 段。

## 8. 何时 MAJOR / MINOR / PATCH

| 类型 | 触发示例 |
|---|---|
| MAJOR | • 删 / 改 console API 路径或字段（外部 UI 已 codegen）<br>• 不可逆 schema 改动（删字段 / 改字段类型）<br>• 改 `LaunchRequest` 等 DTO 必填字段语义 |
| MINOR | • 加新 ADR backend（V11x migration）<br>• 加新 console 端点（不破坏旧端点）<br>• 加可选字段（旧客户端未传也能跑） |
| PATCH | • bug fix 不改外部接口<br>• mapper.xml SQL 调优<br>• 单元测试补全 |

**判定提问**："旧版本部署的客户端跑新版本会出兼容问题吗？"
- 是 → MAJOR；
- 否 + 加东西 → MINOR；
- 否 + 改东西 → PATCH。

## 9. 当前状态（核查于 2026-09-27）

- **GA 版本**：`v1.0.0`（当前唯一 Git release tag）。
- **当前 Maven revision**：`1.0.0`。
- **历史说明**：`1.1.0` / `1.2.0` 曾作为 GA 前开发里程碑使用，但没有对应 release tag，不视为正式发布。
- **下一开发版本**：开始下一发布周期时按已批准的版本规划切换为目标 SNAPSHOT；不得仅依据历史里程碑编号推断下一版本。

## 10. FAQ

**Q：为什么不抄 Spring Cloud 的 CalVer (`2026.5.0`)？**
A：CalVer 优势在"协调多 repo 多团队多组件兼容性"。本项目单 repo 单 PR 单部署，所有模块永远共版，CalVer 收益小、复杂度高。SemVer 表达力对单仓更直接（"我加了 ADR-026 → MINOR"，CalVer 表达不出来）。

**Q：什么时候应该上 BOM 模块？**
A：出现"外部 / 别的 repo 要 import 锁定本仓多模块的兼容版本组合"时。当前所有消费者都在本仓内，不需要。

**Q：跨 SNAPSHOT 边界的本地构建怎么办？**
A：本仓所有模块用 `${revision}`，并通过版本对齐门禁防漂移。只有当前版本策略明确使用 SNAPSHOT 时，才需用 `mvn install` 将本地工件（例如 `batch-common-X.Y.Z-SNAPSHOT`）安装到 `~/.m2` 供 IDE 或其他项目引用。

**Q：v0.x 阶段怎么办？**
A：本项目跳过 v0.x，直接 1.0.0 GA。后续遇到大重构再考虑 0.x 阶段（未来如果要从内部产品转开源 lib，可能会有这个需求）。
