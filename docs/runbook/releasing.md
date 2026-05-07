# Release Flow / 版本发布流程

> 适用：本仓库 `batch-platform` 9 模块（batch-common / batch-trigger / batch-orchestrator / batch-worker-{core,import,export,process,dispatch} / batch-console-api）
>
> 维护规则：发布操作 = 改 `<revision>` + 打 git tag + 更新 `CHANGELOG.md`。Maven 版本入口在根 pom 单点 `<revision>`，所有模块共版（CI-friendly placeholder + flatten-maven-plugin）。

## 1. 版本号规范（SemVer 2.0.0）

格式：`MAJOR.MINOR.PATCH[-PRERELEASE]`

| 形态 | 示例 | 语义 |
|---|---|---|
| `MAJOR` | `2.0.0` | 不向后兼容的破坏性改动（API 删字段 / 行为反向 / DB schema 不可逆） |
| `MINOR` | `1.1.0` | 向后兼容的新功能（加字段 / 加端点 / 加 ADR backend） |
| `PATCH` | `1.0.1` | 向后兼容的 bug fix（不加新功能） |
| `-SNAPSHOT` | `1.1.0-SNAPSHOT` | 开发分支当前正在累积的下一版本（**任何时候 main 分支默认形态**） |
| `-RC.N` | `1.1.0-RC.1` | 准发布候选（QA / 灰度验证用） |
| `-M1` / `-alpha.1` / `-beta.1` | `1.1.0-M1` | 早期里程碑 / 内部预览（可选，本项目暂不强制） |

**主要不抄 Spring Cloud (CalVer + Release Train) 的原因**：
- 单 repo 单 PR 单部署 → 无需 BOM 协调多仓
- 无外部团队 import 你的 BOM
- 9 模块共 `${revision}` 自然一致，不需要"列车"

## 2. 标准发布 flow（main 分支）

### 2.1 平时（开发期）

```bash
# pom.xml 默认 <revision>1.1.0-SNAPSHOT</revision>，所有 build / IT 用此值。
mvn package -DskipTests
mvn -pl batch-orchestrator -am test
```

PR 合并到 main 不动版本号。`-SNAPSHOT` 状态会一直累积新功能 / 新 bug fix。

### 2.2 准备 release（拉 release 分支或直接 main）

确认 main 测试全绿 + 当前 `<revision>` 是 `X.Y.0-SNAPSHOT`，准备发 `X.Y.0`：

```bash
# 1) 改 pom 去掉 -SNAPSHOT
sed -i '' 's|<revision>X.Y.0-SNAPSHOT</revision>|<revision>X.Y.0</revision>|' pom.xml

# 2) 验证
mvn -DskipTests clean package
mvn test

# 3) 更新 CHANGELOG.md：把 [Unreleased] 段重命名为 [X.Y.0] - YYYY-MM-DD

# 4) 提交 release commit
git add pom.xml CHANGELOG.md
git commit -m "release: X.Y.0"

# 5) 打 tag（annotated tag，描述发布内容）
git tag -a vX.Y.0 -m "Release X.Y.0 — <一句话亮点>"

# 6) push
git push origin main
git push origin vX.Y.0
```

### 2.3 立即 bump 下一开发版本

```bash
# 1) 决定下一版本号：
#    - 大概率是 X.(Y+1).0-SNAPSHOT （新功能积累）
#    - 准备发紧急 patch 时用 X.Y.1-SNAPSHOT
sed -i '' 's|<revision>X.Y.0</revision>|<revision>X.(Y+1).0-SNAPSHOT</revision>|' pom.xml

# 2) CHANGELOG.md 添加新的 [Unreleased] 段头

# 3) 提交 + push
git add pom.xml CHANGELOG.md
git commit -m "chore: bump to X.(Y+1).0-SNAPSHOT for next dev cycle"
git push origin main
```

## 3. Patch 发布 flow（hotfix）

main 已经在 `1.2.0-SNAPSHOT`，但生产跑的是 `1.1.0`，需要给 1.1 系列发 `1.1.1` 紧急 fix：

```bash
# 1) 从 v1.1.0 tag 拉 hotfix 分支
git checkout -b hotfix/1.1.1 v1.1.0

# 2) 改 revision 为 1.1.1-SNAPSHOT 进入开发
sed -i '' 's|<revision>1.1.0</revision>|<revision>1.1.1-SNAPSHOT</revision>|' pom.xml
git commit -am "chore: bump to 1.1.1-SNAPSHOT for hotfix"

# 3) cherry-pick 必要的 fix commit（或在该分支直接修）
git cherry-pick <fix-sha>

# 4) 走 §2.2 release flow，tag v1.1.1

# 5) 把 hotfix 反向合回 main（避免 main 漏掉 fix）
git checkout main
git merge hotfix/1.1.1 --no-ff
# 或 cherry-pick 单 fix commit 到 main（不连版本号）
```

## 4. RC / 预览版本 flow

发 `1.2.0-RC.1` 给 QA 验：

```bash
sed -i '' 's|<revision>1.2.0-SNAPSHOT</revision>|<revision>1.2.0-RC.1</revision>|' pom.xml
git commit -am "release: 1.2.0-RC.1"
git tag -a v1.2.0-RC.1 -m "Release Candidate 1 for 1.2.0"
mvn -DskipTests deploy   # 发到 nexus 让 QA 拉
git push origin main vv1.2.0-RC.1

# RC 验证完后回 SNAPSHOT 继续修，或直接发 GA
sed -i '' 's|<revision>1.2.0-RC.1</revision>|<revision>1.2.0-SNAPSHOT</revision>|' pom.xml
git commit -am "chore: back to 1.2.0-SNAPSHOT after RC.1"
```

## 5. Maven 命令速查

| 场景 | 命令 |
|---|---|
| 默认 build（用 pom 中 `<revision>`） | `mvn package -DskipTests` |
| 临时覆盖 revision（不改 pom） | `mvn -Drevision=1.0.5 package` |
| 发到 nexus / artifactory | `mvn -Drevision=X.Y.Z deploy` |
| 干跑 IT（默认 SNAPSHOT 状态） | `mvn -pl batch-orchestrator -am test` |

`flatten-maven-plugin` 在 `install` / `deploy` 期会展开 `${revision}` 为字面量写入 pom，下游消费者拿到的是已展开的版本号 —— 不要绕过此插件。

## 6. Git tag 规范

- 格式 `v<version>`：`v1.0.0` / `v1.1.0-RC.1` / `v2.0.0`
- 必须是 **annotated tag** (`git tag -a`)，不要轻量 tag
- tag 信息至少含：版本号 + 一句话亮点 + 关键变更引用（ADR / migration / commit）
- 描述性 tag（如 `stable-2026-05-07-doc-aligned`）可与版本 tag 并存，但不替代版本 tag

## 7. CHANGELOG.md 维护

每次合并到 main 后顺手往 `## [Unreleased]` 段加一行：

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

**判定提问**："旧版本部署的客户端跑新版本会爆吗？"
- 是 → MAJOR；
- 否 + 加东西 → MINOR；
- 否 + 改东西 → PATCH。

## 9. 当前状态（2026-05-07）

- **GA 版本**：`v1.0.0` @ commit `525e60f0`（含 ADR-012/017/018/020/021/022/023/025/026 backend 全落地、9 模块、Migration 至 V118、1220 tests / 0 failures）
- **stable tag 别名**：`stable-2026-05-07-doc-aligned`（描述性 tag，与 `v1.0.0` 共存）
- **下一开发版本**：`<revision>1.1.0-SNAPSHOT</revision>`（main 分支默认）

## 10. FAQ

**Q：为什么不抄 Spring Cloud 的 CalVer (`2026.5.0`)？**
A：CalVer 优势在"协调多 repo 多团队多组件兼容性"。本项目单 repo 单 PR 单部署，所有模块永远共版，CalVer 收益小、复杂度高。SemVer 表达力对单仓更直接（"我加了 ADR-026 → MINOR"，CalVer 表达不出来）。

**Q：什么时候应该上 BOM 模块？**
A：出现"外部 / 别的 repo 要 import 锁定本仓多模块的兼容版本组合"时。当前所有消费者都在本仓内，不需要。

**Q：跨 SNAPSHOT 边界的本地构建怎么办？**
A：本仓所有模块用 `${revision}`，统一 SNAPSHOT 不会有漂移。如果要消费别的 SNAPSHOT 工件（如本地装的 batch-common-1.1.0-SNAPSHOT 给 IDE 别的项目引用），用 `mvn install` 把当前 SNAPSHOT 装到本地 ~/.m2 即可。

**Q：v0.x 阶段怎么办？**
A：本项目跳过 v0.x，直接 1.0.0 GA。后续遇到大重构再考虑 0.x 阶段（未来如果要从内部产品转开源 lib，可能会有这个需求）。
