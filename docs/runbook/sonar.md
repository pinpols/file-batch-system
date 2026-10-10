# SonarQube 扫描与门禁（SOP）

> 用 SonarQube 建立静态质量基线：Bug / Vulnerability / Code Smell / 覆盖率 / 重复率。
> 本地一键扫描出报告；CI 在 GitHub runner 上使用临时 SonarQube 容器扫描。

## 1. 覆盖范围

- 只扫 Maven reactor 的 Java 模块（命令统一带 `--projects '!batch-e2e-tests'`，e2e 模块不参与）。
- 覆盖率数据来自 JaCoCo XML（仓库 Maven Wrapper 与 POM 对齐至 JaCoCo `0.8.15`）。
- 质量配置：以 Sonar Way 为底复制出自定义 profile **「Batch Platform Sonar Way」**，唯一定制是 S3776 认知复杂度阈值 15 → 20（避免把 CC 16-21 的轻度越限当作必须重构项）。

## 2. 本地扫描

### 前置条件

- `docker`、Java 21 JDK、可执行的仓库 Maven Wrapper `./mvnw`、`curl`、Python 3（默认找 `python3`，可用 `PYTHON_BIN` 或 `PYTHON` 覆盖）；无需单独安装 Maven
- 首次运行会按脚本内已验证的 SonarQube digest 启动容器（端口 9001，可用 `SONAR_PORT` 覆盖）；升级时通过 `SONAR_IMAGE` 显式指定并验证后再更新默认 digest

### 命令

```bash
./scripts/dev/sonar-scan.sh --full        # 全量：先跑测试 + JaCoCo，再扫描（默认模式）
./scripts/dev/sonar-scan.sh --incremental # 只编译不跑测试，以 origin/main 为基线导出变更行报告
./scripts/dev/sonar-scan.sh --incremental --with-tests # 同时运行测试并刷新 JaCoCo
./scripts/dev/sonar-scan.sh --incremental --base-ref <ref> # 指定增量基线
./scripts/dev/sonar-scan.sh --incremental --base-ref HEAD  # 只看当前未提交/未跟踪变更
./scripts/dev/sonar-scan.sh --skip-build  # 跳过构建，复用已有 target/site/jacoco/jacoco.xml
./scripts/dev/sonar-scan.sh --stop        # 停止并删除 SonarQube 容器
```

脚本内置流程：起容器 → 等待 UP → 生成一次性分析 token → 应用自定义 quality profile → 跑分析 → 导出报告。

`--incremental` 不会裁剪 Sonar 的静态分析上下文：它只执行全 reactor 的 `test-compile`，不运行测试，然后以 Git merge-base 到当前工作树的新增/修改 Java 行过滤报告。增量模式使用独立项目键 `<projectKey>-incremental`，且不采集覆盖率，避免污染全量项目的 JaCoCo 基线。需要同时验证覆盖率时显式增加 `--with-tests`。

Java PR 的本地审阅按需运行增量模式，并检查变更行 issues 与待审 Security Hotspots。它仍会分析完整 Maven reactor，再按 Git 变更行生成增量报告，不是只分析改动文件。CI 工作流在 PR、`main` push 和有相关变更的夜间执行；是否属于 required check 以仓库 Ruleset 为准。未触发的夜间 job 不算扫描通过。

增量文件集同时覆盖分支提交、暂存/未暂存修改和未跟踪 Java 文件。基线默认是 `origin/main`，也可用 `SONAR_BASE_REF` 或 `--base-ref` 覆盖。

### 输出

| 产物 | 内容 |
|---|---|
| `reports/sonar/<timestamp>/sonar-report.md` | 摘要：整体指标（NCLOC / Bug / Vulnerability / Security Hotspot / Code Smell / 技术债 / 重复率 / 覆盖率）+ 各模块 BLOCKER~INFO 分布 + BLOCKER 明细 |
| `reports/sonar/<timestamp>/sonar-report.csv` | 全量 issue 明细（severity / type / 组件 / 行号 / 规则 / 描述 / 状态 / effort） |
| `reports/sonar/<timestamp>/sonar-incremental-report.md` | 增量模式摘要，仅统计 Git 变更行上的 OPEN Issue 与待审 Security Hotspot |
| `reports/sonar/<timestamp>/sonar-incremental-report.csv` | 增量 OPEN Issue 明细 |
| `reports/sonar/<timestamp>/sonar-incremental-hotspots.csv` | 增量待审 Security Hotspot 明细 |
| `reports/sonar/<timestamp>/sonar-changed-lines.json` | 增量基线、merge-base 和文件行区间，供审计复核 |
| `reports/sonar/latest` | 软链，始终指向最近一次扫描 |

报告目录是本地产物（gitignored），不提交；需要留档时把关键结果整理到 `docs/verifications/sonar-report-YYYY-MM-DD.md`（仓库已有多份历史快照）。

### 报告标注（triage）

```bash
./scripts/dev/annotate-sonar-report.py
```

读取 `reports/sonar/latest/sonar-report.csv`，为每条 issue 增加 `action`（`FIXED` / `ANNOTATION` / `DEFERRED` / `SKIP_FP` / `SKIP_SPI` / `SKIP_DOMAIN` / `SKIP_THRESHOLD` / `SKIP_BULK` / `KEEP`）和 `note` 两列，输出 `sonar-report-annotated.csv`，便于逐条决策“修 / 延期 / 豁免”。

## 3. CI 扫描

工作流：[`.github/workflows/sonar-gate.yml`](../../.github/workflows/sonar-gate.yml)

- 触发：push `main`、每日夜间、`workflow_dispatch`；手动运行仅允许选择 `main`。Community Build 不支持多分支/PR 分析，因此不配置 `pull_request` 触发；Java PR 仍可运行本地增量扫描。
- 夜间仅在最近 24 小时有 Java、Maven、Sonar 扫描脚本/辅助脚本或该 workflow 改动时执行。
- Maven 测试和 JaCoCo 先运行；随后在 GitHub-hosted runner 本机启动固定 digest 的 SonarQube Community Build 容器（6 GiB、3 CPU），扫描后检查该分析对应的 Sonar Way Quality Gate。
- 每次扫描将新代码定义设为滚动 30 天，并在分析前回读验证；`fetch-depth: 0` 提供 SCM 历史。门禁还会确认项目绑定了至少一条 Quality Gate 条件，在扫描后确认分析任务完成、Quality Gate 状态为 `OK` 且返回了所有配置条件；空门禁或未完整评估均失败。
- 容器只绑定 runner 的 loopback 地址，并在成功、失败或取消后由 workflow cleanup 删除；数据库无持久卷，不保留 issue 状态、分析历史或项目配置。
- 无需 Sonar token/管理员 secret。Sonar 结果报告作为 workflow artifact 保留 7 天。
- 每次运行都是 main 当前代码快照；滚动 30 天的变更判定依靠 Git SCM 历史，不依靠 Sonar 分析历史。系统不能提供跨提交的 issue 生命周期/趋势，也不执行 PR 分支分析。
- Quality Gate 成功只表示该次分析使用的临时项目门禁通过；是否属于 required check 以仓库 Ruleset 为准。
- 源码中已审核的 `@SuppressWarnings("java:S...")` 按 [Java suppression 标准](../standards/java-suppression-registry.md)维护；临时 Sonar 服务不做 issue 状态同步。
- Sonar **不替代**现有 PMD / Spotless / SpotBugs / 依赖扫描 / 测试门禁。

## 4. 质量基线参考

- 2026-08-06 本地验收（`docs/verifications/sonar-report-2026-08-06.md`）：Quality Gate `OK`、新代码覆盖率 `83.0%`（门槛 80%）、新代码违规 `0`。
- 历史全量快照：`docs/verifications/sonar-report-2026-08-05.md` / `sonar-report-2026-08-06.md`。
- 判定口径：以 **新增代码（new code）** 为准；存量 issue 通过 `annotate-sonar-report.py` 分类标注，不阻塞迭代。

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| 端口 9001 被占用 | `SONAR_PORT=9002 ./scripts/dev/sonar-scan.sh` |
| 容器 3 分钟未就绪 | 脚本会自动打印 `docker logs sonarqube-batch --tail 30`，据此排查镜像拉取 / 内存 |
| 全量模式 `--skip-build` 报 “No JaCoCo XML report found” | 先不带 `--skip-build` 跑一次，或手动执行 `./mvnw clean test org.jacoco:jacoco-maven-plugin:0.8.15:report --projects '!batch-e2e-tests'` |
| 增量模式提示找不到基线 | 先 `git fetch` 对应远端，或通过 `--base-ref <本地可解析 ref>` 指定基线 |
| 报告指标出现 `?` | 服务端分析任务未成功完成，查看脚本输出的 task id 与 `docker logs` |
| 扫描完想清理 | `./scripts/dev/sonar-scan.sh --stop` |

## 6. 维护注意

- S3776 阈值与 profile 绑定逻辑在 `sonar-scan.sh` Step 3.5，改动约定时同步更新脚本注释。
- CI 镜像 digest 与启动、清理方式集中在 `sonar-gate.yml`；本地扫描参数集中在 `sonar-scan.sh`。
- 报告格式变更会同时影响 `sonar-scan.sh` Step 5 的内嵌 Python 导出块和 `annotate-sonar-report.py`。
