# 现行文档图整理记录

- 核查日期：2026-10-25。
- 后端基线：`fix/wdi-current@f3d5e400d3b5bd737124b57cf0a38e685432aab2`。
- 前端基线：`fix/wdi-current@662d254e2f8b97f93021de548815470d2291c997`。
- 范围是两份开发工作树及其既有未提交内容；不以冻结 main 的小差异代替。未提交、推送或改动 main。

## 范围与结果

本轮更新 **59 份图文文件**：后端 52 份、前端 7 份；另调整前端统一文档站 Mermaid 默认主题。逐文件数量与语义说明见[清单](document-diagram-catalog-2026-10-25.csv)。

| 口径 | 整理前 | 整理后 |
|---|---:|---:|
| 后端 Mermaid 图块 | 56 | 115 |
| 前端 Mermaid 图块 | 3 | 12 |
| 合计 | 59 | 127 |

增加的图块来自 ASCII 图转换与复杂图拆分，不表示新增系统能力。目录树、代码示例、终端输出、字面量界面线框、原始取证目录树以及明确被取代的 ADR 保留；Helm 图标不是文档流程图，本轮未改。

**归档排除**：本轮写入清单没有任何 `archive/` 或 `archives/` 路径。初始记录 247 份归档文件指纹，首次整合时全部一致。随后同一后端工作树出现本任务之外的并行归档删除和修改；保留这些外部改动，不恢复、不覆盖，不能把整个工作树的最终归档状态宣称为“全部未变”。本轮写入及归档差异原件保存在本地验证产物中。

## 图义修正

- 统一 WorkflowRun 根运行、JOB 节点与 JobInstance 的关系；控制节点、四类文件 Pipeline 和五类内建 Worker 分别表达。
- Worker / SDK 通过 CLAIM、续租与 REPORT 让 Orchestrator 收敛平台状态；文件和阶段证据写入不等于可直接推进平台任务状态。
- 区分“状态与 Outbox 同事务写入”和“发布器异步投递”；FAILED 事件重新 CAS 领取到 PUBLISHING，避免画成无条件回 NEW。
- PROCESS 在 COMMIT 发布结果；DISPATCH 的补偿是元数据/审计收敛，不表示已撤销外部副作用。结果不明保留核对边界。
- 批量日结算、bizDate 来源、Quartz 归属、前端遥测启用条件、CI 并行分支、逻辑关联和实体留存策略按对应代码/配置修正。没有物理外键不能直接推出允许删除。
- 保留 ADR 原决策与未完成方案的历史/提案属性，不把图样式整理写成上线或能力验收。

## 视觉处理

采用中性色、短标签、线性连线和职责分组；复杂关系按主题拆图，去掉无语义的多色样式。四张偏宽图经浏览器复核后再次收窄；最终图块没有宽度大于 1600 或高度大于 1700 的候选，尺寸只用于本轮筛查，不构成业务验收标准。

统一文档站默认使用 `neutral`，暗色由已有 Mermaid 插件切换；图内仅保留布局设置，避免固定亮色主题覆盖暗色。实际文档站页面的两张核心模型图在明暗主题均成功显示，并核对了暗色箭头对比度。

## 验证

| 层级 | 实际执行 | 结果 |
|---|---|---|
| 后端静态 | `python3 scripts/ci/check-docs-structure.py` | 通过 |
| 后端静态 | `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过 |
| 后端静态 | `python3 scripts/ci/check-code-doc-references.py` | 通过 |
| 后端静态 | `python3 scripts/ci/check-terminology-doc-sync.py` | 通过 |
| 前端静态 | `npm run check:docs` | 通过 |
| 图块渲染 | Playwright CLI 驱动本地 Mermaid 11.17.2 预览；亮色/暗色各 127 图 | 各 127/127 成功；抽查流程、状态、ER、类型和时序图截图 |
| 实际站点 | VitePress 1.6.4 / 插件 2.0.17 / Node 24.21.0；以当前两仓 docs 只读汇入临时构建目录 | 客户端、SSR 编译和页面渲染成功；实际核心模型页面明暗主题各 2/2 图显示 |
| 完整构建入口 | `bash /private/tmp/bfs-docs-build-20261025/run-docs-build.sh`，复制仓库 `docs:prepare → vitepress build → docs:size` 原流程 | **退出码 1：体积门禁未通过**；不称完整构建通过 |

当前工作树没有依赖安装目录，本轮复用本机已安装的同版本依赖；临时构建副本、缓存及产物均在 `/private/tmp`，未复制生成站点回源码。

体积门禁的两个超限源文件均不在本轮图文清单：

| 文档 | JS raw 字节 | gzip 字节 |
|---|---:|---:|
| `docs/audit/nested-callsite-inventory-2026-10-21.md` | 1,335,177 | 402,458 |
| `docs/plans/workflow-daily-integration-execution-log-2026-10-14.md` | 2,226,693 | 654,772 |

通用 chunk 预算为 raw 850,000 / gzip 220,000 字节。本轮不放宽门禁、不删减这两份报告；生成站点钩子的既有失效链接降级计数不作为本次新缺陷或修复证明。

本地原件位于 `logs/verification/workflow/document-diagrams-2026-10-25/`，包含逐图亮暗渲染结果、截图、构建日志、文件写入清单和归档并发差异；这些是忽略的运行产物。未运行 Java 产品编译/业务测试、完整 CI 或 staging，本轮结论限于文档与显示验证。
