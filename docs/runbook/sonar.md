# SonarQube 扫描与门禁（SOP）

> 用 SonarQube 建立静态质量基线：Bug / Vulnerability / Code Smell / 覆盖率 / 重复率。
> 本地一键扫描出报告，CI 侧预留质量门禁（默认关闭）。

## 1. 覆盖范围

- 只扫 Maven reactor 的 Java 模块（命令统一带 `--projects '!batch-e2e-tests'`，e2e 模块不参与）。
- 覆盖率数据来自 JaCoCo XML（`mvn clean test org.jacoco:jacoco-maven-plugin:0.8.14:report`）。
- 质量配置：以 Sonar Way 为底复制出自定义 profile **「Batch Platform Sonar Way」**，唯一定制是 S3776 认知复杂度阈值 15 → 20（避免把 CC 16-21 的轻度越限当作必须重构项）。

## 2. 本地扫描

### 前置条件

- `docker`、`mvn`（Java 21）、`curl`、`python3`
- 首次运行会自动拉取 `sonarqube:community` 镜像并启动容器（端口 9001，可用 `SONAR_PORT` 覆盖）

### 命令

```bash
./scripts/dev/sonar-scan.sh               # 全量：先跑测试 + JaCoCo，再扫描
./scripts/dev/sonar-scan.sh --skip-build  # 跳过构建，复用已有 target/site/jacoco/jacoco.xml
./scripts/dev/sonar-scan.sh --stop        # 停止并删除 SonarQube 容器
```

脚本内置流程：起容器 → 等待 UP → 生成一次性分析 token → 应用自定义 quality profile → 跑分析 → 导出报告。

### 输出

| 产物 | 内容 |
|---|---|
| `reports/sonar/<timestamp>/sonar-report.md` | 摘要：整体指标（NCLOC / Bug / Vulnerability / Security Hotspot / Code Smell / 技术债 / 重复率 / 覆盖率）+ 各模块 BLOCKER~INFO 分布 + BLOCKER 明细 |
| `reports/sonar/<timestamp>/sonar-report.csv` | 全量 issue 明细（severity / type / 组件 / 行号 / 规则 / 描述 / 状态 / effort） |
| `reports/sonar/latest` | 软链，始终指向最近一次扫描 |

报告目录是本地产物（gitignored），不提交；需要留档时把关键结果整理到 `docs/verifications/sonar-report-YYYY-MM-DD.md`（仓库已有多份历史快照）。

### 报告标注（triage）

```bash
./scripts/dev/annotate-sonar-report.py
```

读取 `reports/sonar/latest/sonar-report.csv`，为每条 issue 增加 `action`（`FIXED` / `ANNOTATION` / `DEFERRED` / `SKIP_FP` / `SKIP_SPI` / `SKIP_DOMAIN` / `SKIP_THRESHOLD` / `SKIP_BULK` / `KEEP`）和 `note` 两列，输出 `sonar-report-annotated.csv`，便于逐条决策“修 / 延期 / 豁免”。

## 3. CI 门禁（预留，默认关闭）

工作流：[`.github/workflows/sonar-gate.yml`](../../.github/workflows/sonar-gate.yml)

- 触发：PR → `main`、push `main`、`workflow_dispatch`；超时 30 分钟。
- **默认关闭**：只有仓库变量 `SONAR_GATE_ENABLED=true` 才执行，未配置 SonarCloud/SonarQube 凭据时不影响现有 CI 与合并门禁。
- 启用前配置：
  - Secret：`SONAR_TOKEN`
  - Variable：`SONAR_HOST_URL`（可选，默认 `https://sonarcloud.io`）
  - Variable：`SONAR_PROJECT_KEY`（可选，默认 `file-batch-system`）
  - Variable：`SONAR_ORGANIZATION`（SonarCloud 必填；自建 SonarQube 可不填）
- 执行内容：`./mvnw -B clean test org.jacoco:jacoco-maven-plugin:0.8.14:report --projects '!batch-e2e-tests' org.sonarsource.scanner.maven:sonar-maven-plugin:5.7.0.6970:sonar -Dsonar.qualitygate.wait=true`。
- 结果以 Sonar 侧 Quality Gate 为准（`-Dsonar.qualitygate.wait=true`）。
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
| `--skip-build` 报 “No JaCoCo XML report found” | 先不带 `--skip-build` 跑一次，或手动执行 `mvn clean test org.jacoco:jacoco-maven-plugin:0.8.14:report --projects '!batch-e2e-tests'` |
| 报告指标出现 `?` | 服务端分析任务未成功完成，查看脚本输出的 task id 与 `docker logs` |
| 扫描完想清理 | `./scripts/dev/sonar-scan.sh --stop` |

## 6. 维护注意

- S3776 阈值与 profile 绑定逻辑在 `sonar-scan.sh` Step 3.5，改动约定时同步更新脚本注释。
- CI 门禁参数集中在 `sonar-gate.yml` 顶部的 `env` 与 `vars` 引用。
- 报告格式变更会同时影响 `sonar-scan.sh` Step 5 的内嵌 Python 导出块和 `annotate-sonar-report.py`。
