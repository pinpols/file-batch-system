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

**主要不抄 Spring Cloud (CalVer + Release Train) 的原因**：
- 单 repo 单 PR 单部署 → 无需 BOM 协调多仓
- 无外部团队 import 你的 BOM
- 根 reactor 共 `${revision}` 自然一致，不需要"列车"

## 2. 标准发布 flow（main 分支）

### 2.1 平时（开发期）

```bash
# 使用当前根 pom.xml 中的 <revision> 构建；不要在普通 PR 中单独改版本。
mvn package -DskipTests
mvn -pl batch-orchestrator -am test
```

PR 合并到 main 通常不改版本号。只有明确开启新的预发布周期时，才按版本策略切换到 `-SNAPSHOT`；正式版本分支保持正式版本号。

### 2.2 准备 release（拉 release 分支或直接 main）

确认当前 `<revision>`、`CHANGELOG.md` 和最近 GA tag 后，按已批准的版本计划确定发布号 `X.Y.Z`。若从预发布版本发布，使用以下统一版本工具：

```bash
# 同步后端版本落点；GA 版本会更新 Chart / prod image tag 并补正式 Changelog 小节。
bash scripts/ci/bump-version.sh X.Y.Z

# 2) 验证
scripts/ci/check-version-alignment.sh
mvn -DskipTests clean package
mvn test

# 提交 release commit
git add pom.xml load-tests/pom.xml helm docs CHANGELOG.md scripts
git commit -m "release: X.Y.Z"

# 打 tag（annotated tag，描述发布内容）
git tag -a vX.Y.Z -m "Release X.Y.Z — <一句话亮点>"

# push
git push origin main
git push origin vX.Y.Z
```

### 2.3 开启下一开发版本（按需，不自动执行）

正式版本发布后，主干默认保持正式版本号。只有版本规划明确开始新的预发布周期时，才运行版本工具设置下一开发版本；不需要为每次合并自动切成 `SNAPSHOT`。

```bash
# 只有已批准开启下一预发布周期时执行；版本号由版本计划决定。
bash scripts/ci/bump-version.sh X.Y.Z-SNAPSHOT
bash scripts/ci/check-version-alignment.sh
```

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

# 4) 修复验收后设置 GA 版本、检查对齐并按 §2.2 发布
bash scripts/ci/bump-version.sh X.Y.Z
bash scripts/ci/check-version-alignment.sh
git tag -a vX.Y.Z -m "Release X.Y.Z"

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
git tag -a vX.Y.Z-RC.1 -m "Release Candidate 1 for X.Y.Z"
mvn -DskipTests deploy   # 发到 nexus 让 QA 拉
git push origin main vX.Y.Z-RC.1

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
