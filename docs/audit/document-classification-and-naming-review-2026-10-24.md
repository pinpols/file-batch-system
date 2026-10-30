# 文档分类、目录与索引治理 — 2026-10-24

> 类型：本轮增量治理记录。长期规则以 [文档治理规范](../standards/document-governance.md) 为准；实际查找和变更同步按 [文档路由](../standards/document-routing-guide.md)。本记录不替代系统资产与阶段验收矩阵。

## 1. 基线、范围和结果

- 分支：`feature/workflow-daily-integration`；HEAD：`ee01e9751`；处理现有已暂存、未暂存与新增资产，保留既有开发内容；没有提交、推送或创建 PR。
- 当前一级目录由20个收敛到16个。取消当前 analysis/backlog/spike/test-data：工作台账与阶段方案归 plans；可行性计划/证据/采纳决定按 plans/verifications/ADR 管理；场景数据移出 docs。
- 生命周期和更新方式分别判断：长期资产维护原文；阶段方案和矩阵维护原文件；阶段执行日志追加批次；每轮审计、修复盘点、验收按日期新增。系统事实变化同步实际受影响资产，不能只追加问题报告。
- 所有日期文件名统一 YYYY-MM-DD；跨日记录写完整起讫日期，版本保留在主题。旧月度/H2与R3阶段材料按正文审计周期或 Git --follow 首次提交补齐真实日期，并记录依据；不虚构每月1日，也不把整理日当基线。
- 归档分类与当前职责一致，有内容才建立目录；原 analysis/backlog 混合桶取消，统计历史统一在 archive/stats。归档 ADR 关联材料改为业务主题名，关联编号保留正文，正式 ADR 决策编号与状态仍由当前决策入口维护。
- 每份 Markdown 在所属 README 有真实跳转与用途；每个子目录在上级 README 有入口。当前和归档索引均检查，历史正文结论不追溯改写。
- 后端/配对前端 AGENTS 与文档 Skill 引用共同的路由与更新规则；前端保留自己的领域目录，并同步配对后端迁移后的链接。

前序整理完成时盘点：560份 Markdown；归档子目录为 `architecture, audit, design, plans, review, runbook, standards, stats, testing, verifications`。

### 2026-10-25 现行文档归位复核

复核发现，部分架构事实、持续有效的范围边界和已落地设计被作为“历史材料”放进 archive，导致现行文档仍链接归档副本或与实现冲突。现将有效结论并入对应现行资产，删除重复或失效文档；一次性评估和验收证据仍按日期保留。

## 2. 具体纠正

| 原问题 | 处理与当前边界 |
|---|---|
| test-data 混入 docs | 31 SQL、4 XLSX、1 JSON迁到根 test-data/sim 和 tenant-config-packages；36份 payload 的 SHA-256 不变。同步 SIM、生成器、pre-commit、Java/Python 路径契约和 CI 路由；只执行无外部写入的检查 |
| 旧命名策略与现行规则矛盾 | governance/document-naming-and-archive-policy.md 改为兼容入口，规则只在 standards/document-governance.md 维护 |
| analysis 名称与职责不符，backlog/spike 拆得过细 | 两份长期台账归 plans；阶段材料按原职责归位；原延期裁决、外部验收缺口仍可从台账追溯，不因归档自动实施或视为完成 |
| Java 可读性清单带旧日期却每次 pre-push 刷新 | 归入 stats/java-readability-inventory.md，作为当前机器清单；单次人工裁定仍保留日期。CI与本地钩子同步新路径 |
| 可复用兼容矩阵混入一次实测结果 | Runbook 保留支持范围与方法；2026-09-01 实测独立为日期证据，未重新执行历史实测 |
| Sonar 扫描与验收报告混名 | 纯静态扫描归 audit；旧扫描/CSV归 archive/audit。原 sonar-report-2026-08-06.md 实际是 Batch4构建/测试/CI验收，按内容改名并归 archive/verifications |
| 两轮 Worker 报告名称过近 | 2026-09-14 为阶梯/混合负载及Process故障恢复；2026-09-20为完整本地验收、SIM和mixed复测。分别按范围命名，均保留在 verifications，不把后者当作覆盖前者全部场景 |
| 只出现文件名也能通过索引守卫 | 改为实际跳转与用途校验，覆盖当前/归档及子目录；先复现三个漏口，再修守卫并跑正反例 |

Fixture 迁移明细：[test-fixture-path-migration-2026-10-24.json](test-fixture-path-migration-2026-10-24.json)。

## 3. 验证结果

| 检查 | 结果 |
|---|---|
| 变更范围路由、配置包生成器、SIM profile/fault/SFTP契约5个Python测试模块 | 57项通过；其中20项为范围路由。新位置先出现3个失败断言，修正后通过 |
| 索引与日期命名守卫测试 | 11项通过（索引5、日期6）；覆盖无跳转、无用途、归档漏索引、无效日期、月份/H2冒充日期等 |
| 共享 fixture 的 Lean LOC 归属 | 3项通过，先复现2项把SQL/JSON误归生产代码的问题；修正后根test-data归测试组，历史快照建议名使用统一日期后缀 |
| fix-fixture-xlsx.py --check | 4份工作簿检查通过；未重写工作簿 |
| 36份场景 payload SHA-256 | 36/36与迁移前一致；Sonar历史CSV另行移动并保持内容哈希一致 |
| check-docs-structure.py / check-doc-timestamp-policy.py / check-code-doc-references.py | 通过；真实README链接、用途、目录归属、日期与代码文档引用 |
| SLO / SDK runtime / application governance / hardcoded runtime config | 通过；应用治理6个control |
| actionlint full-ci-gate.yml / 修改的SIM与入口Shell bash -n | 通过 |
| 后端documentation-governance与前端frontend-docs-release-governance quick_validate | 通过 |
| 前端 npm run check:docs | 通过，75份当前文档 |
| git diff --check | 通过 |
| 前端 npm run docs:build（统一站点） | **未通过**。VitePress编译和渲染完成，完整命令被既有体积门禁阻断，详见下节 |

未运行完整SIM、Java数据库集成、真实配置包上传或目标环境验收；目录与路径检查通过不代表本轮系统功能验收完成。历史记录中的成功数字只适用于原基线，未改为当前结论。

## 4. 尚未关闭的文档站问题

两次实际运行 docs:build 均完成 VitePress 编译和页面渲染，最终 DOCS_BUNDLE_SIZE 退出1。最后一次阻断：

- audit/nested-callsite-inventory-2026-10-21：页面JS raw 1,332,957 bytes / gzip 401,654 bytes。
- plans/workflow-daily-integration-execution-log-2026-10-14：页面JS raw 2,156,740 bytes / gzip 629,359 bytes。
- 既有通用预算为 raw 850KB / gzip 220KB，没有扩大预算、排除页面或隐藏内容绕过。两份是仍被追加的现有记录；本轮没有为通过预算而拆改其持续写入入口或丢弃历史。

问题已登记到 [TODO Master](../plans/todo-master.md) 的 DOCS-SIZE-2026-10-24；后续分段需核实追加入口、历史锚点和全部证据保留。站点构建器对历史/仓库外引用的既有 neutralized 输出也不等同于所有历史正文链接已重新验收；本轮强制验证的是当前文档和所有 README 导航。

## 5. 旧路径与现归属

下表覆盖相对HEAD的文件迁移和旧索引接管映射，供历史引用追溯。已发布Flyway迁移保持原内容/校验和，注释中的旧审计路径视为历史出处；当前报告可通过此表定位。

| 原路径 | 当前归属 |
|---|---|
| `docs/analysis/README.md` | [docs/plans/README.md](../plans/README.md) |
| `docs/analysis/bounded-context-migration-plan-2026-08-03.md` | [docs/plans/bounded-context-migration-plan-2026-08-03.md](../plans/bounded-context-migration-plan-2026-08-03.md) |
| `docs/analysis/build-time-optimization-2026-09-12.md` | [docs/verifications/build-time-optimization-2026-09-12.md](../verifications/build-time-optimization-2026-09-12.md) |
| `docs/analysis/ci-gate-runtime-optimization-2026-10-07.md` | [docs/verifications/ci-gate-runtime-optimization-2026-10-07.md](../verifications/ci-gate-runtime-optimization-2026-10-07.md) |
| `docs/analysis/commit-review-2026-06-05-to-09.md` | [docs/review/commit-review-2026-06-05-to-2026-06-09.md](../review/commit-review-2026-06-05-to-2026-06-09.md) |
| `docs/analysis/competitive-gap-analysis-2026-05-30.md` | [docs/architecture/system-scope-boundary.md](../architecture/system-scope-boundary.md) |
| `docs/analysis/dba-schema-review-2026-05-20.md` | [docs/archive/audit/dba-schema-review-2026-05-20.md](../archive/audit/dba-schema-review-2026-05-20.md) |
| `docs/analysis/deep-issue-analysis.md` | [docs/archive/audit/deep-issue-analysis-2026-04-30.md](../archive/audit/deep-issue-analysis-2026-04-30.md) |
| `docs/analysis/fix-report.md` | [docs/archive/verifications/fix-report-2026-04-21.md](../archive/verifications/fix-report-2026-04-21.md) |
| `docs/analysis/go-live-realism-audit-2026-06-21.md` | [docs/standards/go-live-audit-checklist.md](../standards/go-live-audit-checklist.md) |
| `docs/analysis/hardening-backlog.md` | [docs/plans/hardening-backlog.md](../plans/hardening-backlog.md) |
| `docs/analysis/industry-benchmark-improvement-plan.md` | [docs/architecture/adr/ADR-028-sensor-wait-node.md](../architecture/adr/ADR-028-sensor-wait-node.md) |
| `docs/analysis/infra-port-abstraction-sonar-review-2026-09-25.md` | [docs/audit/infra-port-abstraction-sonar-review-2026-09-25.md](infra-port-abstraction-sonar-review-2026-09-25.md) |
| `docs/analysis/interface-abstraction-governance-2026-09-25.md` | [docs/audit/interface-abstraction-governance-2026-09-25.md](interface-abstraction-governance-2026-09-25.md) |
| `docs/analysis/java-readability-inventory-2026-08-12.md` | [docs/stats/java-readability-inventory.md](../stats/java-readability-inventory.md) |
| `docs/analysis/java-readability-phase-0-classification-2026-08-12.md` | [docs/audit/java-readability-phase-0-classification-2026-08-12.md](java-readability-phase-0-classification-2026-08-12.md) |
| `docs/analysis/java-semantic-expression-scan-2026-08-26.md` | [docs/audit/java-semantic-expression-scan-2026-08-26.md](java-semantic-expression-scan-2026-08-26.md) |
| `docs/analysis/jdk-feature-usage-analysis-2026-06-09.md` | [docs/architecture/runtime-compatibility-contract.md](../architecture/runtime-compatibility-contract.md) |
| `docs/analysis/p0-p1-governance-status-2026-09-02.md` | [docs/runbook/go-live-readiness.md](../runbook/go-live-readiness.md) |
| `docs/analysis/p1a-stage1-migration-plan-2026-05-30.md` | [docs/plans/p1a-stage1-migration-plan-2026-05-30.md](../plans/p1a-stage1-migration-plan-2026-05-30.md) |
| `docs/analysis/project-assessment.md` | [docs/architecture/engineering-maturity-roadmap.md](../architecture/engineering-maturity-roadmap.md) |
| `docs/analysis/redis-mq-abstraction-governance-2026-09-25.md` | [docs/audit/redis-mq-abstraction-governance-2026-09-25.md](redis-mq-abstraction-governance-2026-09-25.md) |
| `docs/analysis/robustness-deep-scan-2026-06-10.md` | [docs/audit/robustness-deep-scan-2026-06-10.md](robustness-deep-scan-2026-06-10.md) |
| `docs/analysis/rustfs-poc-2026-09-25.md` | [docs/verifications/rustfs-poc-2026-09-25.md](../verifications/rustfs-poc-2026-09-25.md) |
| `docs/analysis/s3-compatible-backend-comparison-2026-09-25.md` | [docs/verifications/s3-compatible-backend-comparison-2026-09-25.md](../verifications/s3-compatible-backend-comparison-2026-09-25.md) |
| 扩容现状与 biz 路由决策 | [biz 多租户分片路由](../runbook/biz-tenant-routing.md)；容量实测见 [单机吞吐上限](../verifications/multitenant-peak-single-node-ceiling-2026-06-13.md) |
| `docs/analysis/schema-optimization-review-2026-09-07.md` | [docs/audit/schema-optimization-review-2026-09-07.md](schema-optimization-review-2026-09-07.md) |
| `docs/analysis/script-runtime-compatibility-matrix-2026-09-01.md` | [docs/runbook/script-runtime-compatibility-matrix.md](../runbook/script-runtime-compatibility-matrix.md) |
| `docs/analysis/seaweedfs-poc-2026-09-25.md` | [docs/verifications/seaweedfs-poc-2026-09-25.md](../verifications/seaweedfs-poc-2026-09-25.md) |
| `docs/analysis/single-worker-tens-of-thousands-capacity-2026-06-21.md` | [docs/audit/single-worker-capacity-assessment-2026-06-21.md](single-worker-capacity-assessment-2026-06-21.md) |
| `docs/analysis/singleton-resource-lifecycle-review-2026-10-04.md` | [docs/review/singleton-resource-lifecycle-review-2026-10-04.md](../review/singleton-resource-lifecycle-review-2026-10-04.md) |
| `docs/analysis/sonar-debt-triage-2026-10-10.md` | [docs/audit/sonar-debt-triage-2026-10-10.md](sonar-debt-triage-2026-10-10.md) |
| `docs/analysis/sql-config-governance-report-2026-06-08.md` | [docs/audit/sql-config-governance-report-2026-06-08.md](sql-config-governance-report-2026-06-08.md) |
| `docs/analysis/system-scope-boundary.md` | [docs/architecture/system-scope-boundary.md](../architecture/system-scope-boundary.md) |
| `docs/analysis/system-wide-capability-gap-analysis-2026-06-20.md` | [docs/archive/audit/system-wide-capability-gap-analysis-2026-06-20.md](../archive/audit/system-wide-capability-gap-analysis-2026-06-20.md) |
| `docs/analysis/todo-master.md` | [docs/plans/todo-master.md](../plans/todo-master.md) |
| `docs/architecture/deficiencies-2026-05-30.md` | [docs/architecture/engineering-maturity-roadmap.md](../architecture/engineering-maturity-roadmap.md) |
| `docs/architecture/maturity-assessment.md` | [docs/architecture/engineering-maturity-roadmap.md](../architecture/engineering-maturity-roadmap.md) |
| `docs/architecture/p0-p1-p2-roadmap.md` | [docs/architecture/engineering-maturity-roadmap.md](../architecture/engineering-maturity-roadmap.md) |
| `docs/architecture/quartz-replacement-design.md` | [ADR-033：时间轮替换提案已撤销](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md) |
| `docs/architecture/quartz-replacement-evaluation.md` | [docs/architecture/adr/ADR-033-quartz-to-wheel-scheduler.md](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md) |
| `docs/architecture/rework-classification.md` | [docs/runbook/production-capacity-governance.md](../runbook/production-capacity-governance.md) |
| `docs/architecture/runtime-compatibility-contract-2026-09-01.md` | [docs/architecture/runtime-compatibility-contract.md](../architecture/runtime-compatibility-contract.md) |
| `docs/architecture/scalability-assessment.md` | [docs/runbook/production-capacity-governance.md](../runbook/production-capacity-governance.md) |
| `docs/archive/analysis/2026-06-02-deep-review-round-2.md` | [docs/sdk/sdk-parity-matrix.md](../sdk/sdk-parity-matrix.md) |
| `docs/archive/analysis/2026-06-02-java-python-sdk-deep-review.md` | [docs/archive/review/sdk-code-deep-review-2026-06-02.md](../archive/review/sdk-code-deep-review-2026-06-02.md) |
| `docs/archive/analysis/2026-06-02-sdk-atomic-fe-deep-review.md` | [docs/sdk/wire-protocol.md](../sdk/wire-protocol.md) |
| `docs/archive/analysis/2026-06-02-sdk-code-deep-review.md` | [docs/archive/review/sdk-code-deep-review-2026-06-02.md](../archive/review/sdk-code-deep-review-2026-06-02.md) |
| `docs/archive/analysis/2026-06-02-sdk-fix-execution-plan.md` | [docs/sdk/sdk-parity-matrix.md](../sdk/sdk-parity-matrix.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-arch-tx-tenant.md` | [docs/coding-conventions.md](../coding-conventions.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-architecture-v2.md` | [docs/architecture/bounded-context-rules.md](../architecture/bounded-context-rules.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-architecture.md` | [docs/coding-conventions.md](../coding-conventions.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-business-ops.md` | [docs/archive/audit/deep-scan-be-business-ops-2026-06-03.md](../archive/audit/deep-scan-be-business-ops-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-resources-scheduling-v2.md` | [docs/runbook/jvm-tuning-and-profiling.md](../runbook/jvm-tuning-and-profiling.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-resources-scheduling.md` | [docs/runbook/production-capacity-governance.md](../runbook/production-capacity-governance.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-security.md` | [docs/archive/audit/deep-scan-be-security-2026-06-03.md](../archive/audit/deep-scan-be-security-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-cicd-ops.md` | [docs/runbook/ci.md](../runbook/ci.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-feedback.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-interaction.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-layout-responsive-v2.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-layout-responsive.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-theme-color-a11y-v2.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-theme-color-a11y.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-usability.md` | [docs/audit/product-journey-adversarial-audit-2026-10-10.md](product-journey-adversarial-audit-2026-10-10.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-sdk-data-integrity.md` | [docs/sdk/wire-protocol.md](../sdk/wire-protocol.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-summary.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/2026-06-03-p2-items-evaluation.md` | [docs/coding-conventions.md](../coding-conventions.md) |
| `docs/archive/analysis/2026-06-03-sdk-test-parity-gap.md` | [docs/sdk/sdk-parity-matrix.md](../sdk/sdk-parity-matrix.md) |
| `docs/archive/analysis/2026-06-10-full-audit.md` | [docs/archive/audit/full-audit-2026-06-10.md](../archive/audit/full-audit-2026-06-10.md) |
| Worker 能力优先级与范围 | [系统职责范围](../architecture/system-scope-boundary.md) + `docs/agent-baseline.md` 架构约束 |
| 文件束聚合二阶段方案（历史 analysis 材料） | [docs/architecture/adr/ADR-046-file-bundle-aggregation.md](../architecture/adr/ADR-046-file-bundle-aggregation.md) |
| `docs/archive/analysis/audit-campaign-2026-05-15-complete.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/audit-r6-r7-2026-05-15.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/backend-acceptance-report-2026-05-14.md` | [docs/archive/verifications/backend-acceptance-report-2026-05-14.md](../archive/verifications/backend-acceptance-report-2026-05-14.md) |
| `docs/archive/analysis/backend-audit-fixes-2026-05-28.md` | [docs/plans/todo-master.md](../plans/todo-master.md) |
| `docs/archive/analysis/backend-deep-scan-bug-design-review-2026-05-14.md` | [docs/archive/review/backend-deep-scan-bug-design-review-2026-05-14.md](../archive/review/backend-deep-scan-bug-design-review-2026-05-14.md) |
| `docs/archive/analysis/backend-deep-scan-code-review-2026-05-16.md` | [docs/archive/review/backend-deep-scan-code-review-2026-05-16.md](../archive/review/backend-deep-scan-code-review-2026-05-16.md) |
| `docs/archive/analysis/backend-go-live-deep-scan-2026-05-18.md` | [docs/archive/audit/backend-go-live-deep-scan-2026-05-18.md](../archive/audit/backend-go-live-deep-scan-2026-05-18.md) |
| `docs/archive/analysis/batch-day-instantiation-vs-industry-2026-05-03.md` | [docs/design/batch-day-design.md](../design/batch-day-design.md) |
| `docs/archive/analysis/batch-system-timezone-consistency-check-2026-05-05.md` | [docs/design/time-date-semantics.md](../design/time-date-semantics.md) |
| `docs/archive/analysis/code-change-upgrade-scope-2026-05-23.md` | [docs/runbook/quarterly-dependency-governance.md](../runbook/quarterly-dependency-governance.md) |
| `docs/archive/analysis/comprehensive-audit-round1-4-2026-05.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/comprehensive-project-analysis-2026-04-09.md` | [docs/architecture/security-model.md](../architecture/security-model.md) |
| `docs/archive/analysis/dba-schema-review-2026-05-20.md` | [docs/archive/audit/dba-schema-review-2026-05-20.md](../archive/audit/dba-schema-review-2026-05-20.md) |
| `docs/archive/analysis/deep-issue-analysis-v1.md` | [docs/archive/audit/deep-issue-analysis-v1-2026-04-15.md](../archive/audit/deep-issue-analysis-v1-2026-04-15.md) |
| `docs/archive/analysis/deep-issue-analysis-v2.md` | [docs/archive/audit/deep-issue-analysis-v2-2026-04-15.md](../archive/audit/deep-issue-analysis-v2-2026-04-15.md) |
| `docs/archive/analysis/deep-issue-analysis-v3.md` | [docs/archive/audit/deep-issue-analysis-v3-2026-04-20.md](../archive/audit/deep-issue-analysis-v3-2026-04-20.md) |
| `docs/archive/analysis/dependency-upgrade-evaluation-2026-05-23.md` | [docs/runbook/quarterly-dependency-governance.md](../runbook/quarterly-dependency-governance.md) |
| `docs/archive/analysis/design-patterns-evaluation-2026-04-09.md` | [docs/audit/design-pattern-review-2026-10-21.md](design-pattern-review-2026-10-21.md) |
| `docs/archive/analysis/disabled-tests-root-cause-2026-05-21.md` | [docs/standards/ci-test-quality-governance.md](../standards/ci-test-quality-governance.md) |
| `docs/archive/analysis/engineering-backlog-2026-04-09.md` | [docs/plans/todo-master.md](../plans/todo-master.md) |
| `docs/archive/analysis/file-io-evaluation-2026-05-03.md` | [docs/architecture/heavy-workload-guarantees.md](../architecture/heavy-workload-guarantees.md) |
| `docs/archive/analysis/fix-report-v1.md` | [docs/archive/verifications/fix-report-v1-2026-04-15.md](../archive/verifications/fix-report-v1-2026-04-15.md) |
| `docs/archive/analysis/fix-report-v2.md` | [docs/archive/verifications/fix-report-v2-2026-04-15.md](../archive/verifications/fix-report-v2-2026-04-15.md) |
| `docs/archive/analysis/fix-report-v3.md` | [docs/archive/verifications/fix-report-v3-2026-04-20.md](../archive/verifications/fix-report-v3-2026-04-20.md) |
| `docs/archive/analysis/four-round-deep-scan-campaign-2026-05-15.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| `docs/archive/analysis/frontend-backend-contract-cleanup-2026-05-19.md` | [docs/api/console-api-protocol.md](../api/console-api-protocol.md) |
| `docs/archive/analysis/frontend-issue-handoff-2026-05-17.md` | [docs/api/console-api-protocol.md](../api/console-api-protocol.md) |
| `docs/archive/analysis/hardening-backlog-v3.md` | [docs/plans/hardening-backlog.md](../plans/hardening-backlog.md) |
| `docs/archive/analysis/hardening-backlog-v4.md` | [docs/plans/hardening-backlog.md](../plans/hardening-backlog.md) |
| `docs/archive/analysis/hardening-backlog-v6.md` | [docs/plans/hardening-backlog.md](../plans/hardening-backlog.md) |
| `docs/archive/analysis/implementation-status-2026-04-09.md` | [docs/plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md](../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md) |
| `docs/archive/analysis/issue-implementation-roadmap-2026-04-09.md` | [docs/plans/todo-master.md](../plans/todo-master.md) |
| `docs/archive/analysis/orchestrator-vs-industry-2026-05-03.md` | [docs/archive/audit/orchestrator-vs-industry-2026-05-03.md](../archive/audit/orchestrator-vs-industry-2026-05-03.md) |
| `docs/archive/analysis/persistence-and-test-architecture-2026-05-02.md` | [docs/testing/test-fixture-guide.md](../testing/test-fixture-guide.md) |
| `docs/archive/analysis/pg-schema-audit-2026-05-03.md` | [docs/design/database-schema-governance.md](../design/database-schema-governance.md) |
| `docs/archive/analysis/positional-args-cleanup-plan.md` | [docs/coding-conventions.md](../coding-conventions.md) |
| `docs/archive/analysis/project-assessment-2026-04-29.md` | [docs/architecture/security-model.md](../architecture/security-model.md) |
| `docs/archive/analysis/rename-spi-to-atomic-2026-05-31.md` | [docs/architecture/adr/ADR-029-dedicated-spi-worker.md](../architecture/adr/ADR-029-dedicated-spi-worker.md) |
| `docs/archive/analysis/runtime-issues-snapshot-2026-04-25.md` | [docs/runbook/troubleshooting-decision-tree.md](../runbook/troubleshooting-decision-tree.md) |
| `docs/archive/analysis/security-scan-2026-05-16.md` | [docs/archive/audit/security-scan-2026-05-16.md](../archive/audit/security-scan-2026-05-16.md) |
| `docs/archive/analysis/sonar-cleanup-2026-05-02.md` | [docs/archive/verifications/sonar-cleanup-2026-05-02.md](../archive/verifications/sonar-cleanup-2026-05-02.md) |
| `docs/archive/analysis/system-deep-analysis-2026-04-09.md` | [docs/audit/backend-adversarial-audit-2026-07-27.md](backend-adversarial-audit-2026-07-27.md) |
| `docs/archive/analysis/system-multidimensional-improvement-analysis-2026-04-09.md` | [docs/plans/development-worktree-governance-2026-10-24.md](../plans/development-worktree-governance-2026-10-24.md) |
| `docs/archive/analysis/system-scope-boundary-audit-2026-05-06.md` | [docs/architecture/system-scope-boundary.md](../architecture/system-scope-boundary.md) |
| Worker 能力优先级评估（历史 analysis 材料） | [docs/architecture/worker-plugins.md](../architecture/worker-plugins.md) |
| `docs/archive/analysis/worker-vs-industry-2026-05-03.md` | [docs/archive/audit/worker-vs-industry-2026-05-03.md](../archive/audit/worker-vs-industry-2026-05-03.md) |
| Workflow Editor 方案与实现说明 | [Workflow DAG 编辑器设计与实现基线](../design/workflow-dag-designer.md)；以配对前端当前 X6 编辑器实现为准 |
| `docs/archive/analysis/workflow-ui-redesign-campaign-2026-05-15.md` | [docs/design/workflow-dag-designer.md](../design/workflow-dag-designer.md) |
| `docs/archive/architecture/design-gap-audit-2026-04-09.md` | [docs/architecture/architecture-truth.md](../architecture/architecture-truth.md) |
| `docs/archive/architecture/infrastructure-performance-analysis-2026-04-09.md` | [docs/runbook/production-capacity-governance.md](../runbook/production-capacity-governance.md) |
| `docs/archive/architecture/long-parameter-methods-audit-2026-04-09.md` | [docs/coding-conventions.md](../coding-conventions.md) |
| `docs/archive/architecture/naming-refactor-candidates-2026-04-09.md` | [docs/architecture/core-model.md](../architecture/core-model.md) |
| `docs/archive/architecture/scalability-fix-plan-2026-04-09.md` | [docs/runbook/ha-readiness.md](../runbook/ha-readiness.md) |
| `docs/archive/architecture/scalability-ha-assessment-2026-03-26.md` | [docs/runbook/ha-readiness.md](../runbook/ha-readiness.md) |
| Worker 批量消费方案（历史 backlog 材料） | [docs/architecture/adr/ADR-046-file-bundle-aggregation.md](../architecture/adr/ADR-046-file-bundle-aggregation.md) |
| `docs/archive/backlog/be-acceptance-2026-07-13.md` | [docs/testing/be-acceptance-template.md](../testing/be-acceptance-template.md) |
| `docs/archive/backlog/export-partition-keyset-range-2026-06-06.md` | [docs/plans/export-partition-keyset-range-2026-06-06.md](../plans/export-partition-keyset-range-2026-06-06.md) |
| `docs/archive/backlog/export-partition-slice-fix-2026-06-06.md` | [docs/plans/export-partition-slice-2026-06-06.md](../plans/export-partition-slice-2026-06-06.md) |
| `docs/archive/backlog/import-line-mod-read-amplification-2026-06-05.md` | [docs/design/file-pipeline-design.md](../design/file-pipeline-design.md) |
| `docs/archive/design/mega-chapter-map.md` | [docs/design/legacy-design-chapter-map.md](../design/legacy-design-chapter-map.md) |
| `docs/archive/testing/historical-test-reports-2026-04-09-10.md` | [docs/archive/verifications/historical-test-reports-2026-04-09-to-2026-04-10.md](../archive/verifications/historical-test-reports-2026-04-09-to-2026-04-10.md) |
| `docs/archive/testing/test-errors-2026-04-09.md` | [docs/runbook/be-acceptance.md](../runbook/be-acceptance.md) |
| `docs/archive/testing/test-run-snapshot-2026-04-09.md` | [docs/archive/verifications/historical-test-reports-2026-04-09-to-2026-04-10.md](../archive/verifications/historical-test-reports-2026-04-09-to-2026-04-10.md) |
| `docs/audit/INDEX-2026-05-23.md` | [docs/audit/module-architecture-audit-index-2026-05-23.md](module-architecture-audit-index-2026-05-23.md) |
| `docs/audit/convention-drift-guard-index.md` | [docs/standards/convention-drift-guard-index.md](../standards/convention-drift-guard-index.md) |
| 到达组坏数据隔离决策 | [ADR-046 当前处理边界](../architecture/adr/ADR-046-file-bundle-aggregation.md#到达组确定性坏数据的当前处理) |
| `docs/backlog/be-acceptance-template.md` | [docs/testing/be-acceptance-template.md](../testing/be-acceptance-template.md) |
| `docs/backlog/ci-external-action-upgrade-backlog-2026-09-30.md` | [docs/audit/ci-external-action-upgrade-review-2026-09-30.md](ci-external-action-upgrade-review-2026-09-30.md) |
| `docs/backlog/cross-platform-scripts-2026-05-24.md` | [docs/runbook/script-runtime-compatibility-matrix.md](../runbook/script-runtime-compatibility-matrix.md) |
| `docs/backlog/log-noise-2026-06-02.md` | [docs/design/logging-architecture.md](../design/logging-architecture.md) |
| `docs/backlog/outstanding-work-2026-06-23.md` | [docs/plans/todo-master.md](../plans/todo-master.md) |
| `docs/backlog/platform-capability-evolution-backlog-2026-09-26.md` | [docs/plans/platform-capability-evolution-plan-2026-09-26.md](../plans/platform-capability-evolution-plan-2026-09-26.md) |
| `docs/backlog/pre-production-capacity-optimization-plan-2026-06-08.md` | [docs/plans/pre-production-capacity-optimization-plan-2026-06-08.md](../plans/pre-production-capacity-optimization-plan-2026-06-08.md) |
| `docs/backlog/rest-command-api-toggle-governance-2026-09-26.md` | [docs/api/console-api-protocol.md](../api/console-api-protocol.md) |
| `docs/backlog/single-node-throughput-optimization-2026-06-06.md` | [docs/verifications/streaming-large-file-import-export-2026-06-06.md](../verifications/streaming-large-file-import-export-2026-06-06.md) |
| `docs/backlog/worker-throughput-benchmark-plan-2026-06-07.md` | [docs/verifications/control-plane-worker-throughput-2026-06-07.md](../verifications/control-plane-worker-throughput-2026-06-07.md) |
| `docs/design/api-gap-analysis.md` | [docs/api/console-api-protocol.md](../api/console-api-protocol.md) |
| `docs/design/file-integrity-sidecar-manifest-design-2026-06-07.md` | [docs/design/file-integrity-sidecar-manifest-design.md](../design/file-integrity-sidecar-manifest-design.md) |
| `docs/design/import-export-column-mapping-default-inference-2026-06-20.md` | [docs/design/import-export-column-mapping-default-inference.md](../design/import-export-column-mapping-default-inference.md) |
| `docs/design/process-worker-known-issues.md` | [docs/archive/audit/process-worker-known-issues-2026-04-28.md](../archive/audit/process-worker-known-issues-2026-04-28.md) |
| 项目结构与 POM 设计 | [当前项目结构与 Maven reactor](../architecture/project-structure.md) |
| `docs/design/sdk-industry-benchmark.md` | [docs/sdk/byo-sdk-guide.md](../sdk/byo-sdk-guide.md) |
| `docs/plans/2026-06-06-export-partition-keyset-range.md` | [docs/plans/export-partition-keyset-range-2026-06-06.md](../plans/export-partition-keyset-range-2026-06-06.md) |
| `docs/plans/2026-06-06-export-partition-slice.md` | [docs/plans/export-partition-slice-2026-06-06.md](../plans/export-partition-slice-2026-06-06.md) |
| `docs/plans/2026-06-06-migrate-minio-sdk-to-aws-sdk-v2.md` | [docs/plans/migrate-minio-sdk-to-aws-sdk-v2-2026-06-06.md](../plans/migrate-minio-sdk-to-aws-sdk-v2-2026-06-06.md) |
| `docs/plans/2026-06-13-ha-deployment-plan.md` | [docs/plans/ha-deployment-plan-2026-06-13.md](../plans/ha-deployment-plan-2026-06-13.md) |
| `docs/plans/ai-integration-plan-2026-07.md` | [docs/plans/ai-integration-plan-2026-07-10.md](../plans/ai-integration-plan-2026-07-10.md) |
| `docs/plans/alertmanager-migration-plan-2026-07.md` | [docs/plans/alertmanager-migration-plan-2026-07-12.md](../plans/alertmanager-migration-plan-2026-07-12.md) |
| `docs/plans/backend-borrowings-and-improvements-2026-07.md` | [docs/plans/backend-borrowings-and-improvements-2026-07-10.md](../plans/backend-borrowings-and-improvements-2026-07-10.md) |
| `docs/plans/checkpoint-resume-design-2026-07.md` | [docs/plans/checkpoint-resume-design-2026-07-12.md](../plans/checkpoint-resume-design-2026-07-12.md) |
| `docs/plans/fe-worklist-2026-h2-atomic-sdk.md` | [docs/plans/fe-atomic-sdk-h2-worklist-2026-06-01.md](../plans/fe-atomic-sdk-h2-worklist-2026-06-01.md) |
| `docs/plans/ipv6-happy-eyeballs-rollout-2026-09.md` | [docs/plans/ipv6-happy-eyeballs-rollout-2026-09-24.md](../plans/ipv6-happy-eyeballs-rollout-2026-09-24.md) |
| `docs/plans/r3-1-chaos-toxiproxy-it.md` | [docs/plans/r3-1-chaos-toxiproxy-it-2026-05-29.md](../plans/r3-1-chaos-toxiproxy-it-2026-05-29.md) |
| `docs/plans/r3-2-runbook-playbooks.md` | [docs/plans/r3-2-runbook-playbooks-2026-05-29.md](../plans/r3-2-runbook-playbooks-2026-05-29.md) |
| `docs/plans/r3-3-soak-tests.md` | [docs/plans/r3-3-soak-tests-2026-05-29.md](../plans/r3-3-soak-tests-2026-05-29.md) |
| `docs/plans/r3-4-forensic-replay.md` | [docs/plans/r3-4-forensic-replay-2026-05-29.md](../plans/r3-4-forensic-replay-2026-05-29.md) |
| `docs/plans/sdk-roadmap-2026-h2-progress.md` | [docs/plans/sdk-h2-roadmap-progress-2026-05-31.md](../plans/sdk-h2-roadmap-progress-2026-05-31.md) |
| `docs/plans/sdk-roadmap-2026-h2.md` | [docs/plans/sdk-h2-roadmap-2026-05-31.md](../plans/sdk-h2-roadmap-2026-05-31.md) |
| `docs/runbook/archive/index-consolidation-2026-05.md` | [docs/design/database-schema-governance.md](../design/database-schema-governance.md) |
| `docs/runbook/archive/partition-cutover-2026-05.md` | [docs/runbook/pg-table-partitioning.md](../runbook/pg-table-partitioning.md) |
| `docs/runbook/ci-cd-followup-2026-05-22.md` | [docs/runbook/compose-cd-roadmap.md](../runbook/compose-cd-roadmap.md) |
| `docs/runbook/ci-speedup-2026-06-02.md` | [docs/runbook/ci.md](../runbook/ci.md) |
| `docs/runbook/e2e-it-optimization-2026-05-22.md` | [docs/archive/verifications/e2e-it-optimization-2026-05-22.md](../archive/verifications/e2e-it-optimization-2026-05-22.md) |
| `docs/runbook/gitops-onboarding-2026-05-22.md` | [docs/runbook/gitops-onboarding.md](../runbook/gitops-onboarding.md) |
| `docs/runbook/go-live-realism-audit-2026-06-21.md` | [docs/standards/go-live-audit-checklist.md](../standards/go-live-audit-checklist.md) |
| `docs/runbook/minio-lifecycle-policy.md` | [docs/runbook/object-storage-lifecycle-policy.md](../runbook/object-storage-lifecycle-policy.md) |
| `docs/runbook/password-security-backlog.md` | [docs/design/multi-tenant-and-security.md](../design/multi-tenant-and-security.md) |
| `docs/runbook/sql-audit-2026-05-20.md` | [docs/archive/audit/sql-audit-2026-05-20.md](../archive/audit/sql-audit-2026-05-20.md) |
| `docs/runbook/tia-poc-2026-05-22.md` | [docs/archive/verifications/tia-poc-2026-05-22.md](../archive/verifications/tia-poc-2026-05-22.md) |
| `docs/runbook/worker-stage-coverage.md` | [docs/verifications/worker-stage-coverage-2026-04-25.md](../verifications/worker-stage-coverage-2026-04-25.md) |
| `docs/sdk/conformance-gap-analysis-2026-06-16.md` | [docs/sdk/byo-conformance-contract.md](../sdk/byo-conformance-contract.md) |
| 导出文件恢复选型 | [ADR-038 平台 Worker 断点续跑](../architecture/adr/ADR-038-platform-worker-checkpoint-resume.md) + [运行手册](../runbook/platform-worker-checkpoint-howto.md) |
| `docs/spike/resilience4j-sb4-compat.md` | [docs/verifications/resilience4j-sb4-compat-2026-09-25.md](../verifications/resilience4j-sb4-compat-2026-09-25.md) |
| `docs/stats/archive/README.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-05-29.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-06-03.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-06-09.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-07-13.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-07-19.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-07-22-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-07-31-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-08-02-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-08-04-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-08-09-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-09-01-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/stats/archive/loc-2026-09-24-lean.md` | [docs/stats/README.md](../stats/README.md) |
| `docs/test-data/sim-bundle-import-fixtures.sql` | [test-data/sim/sim-bundle-import-fixtures.sql](../../test-data/sim/sim-bundle-import-fixtures.sql) |
| `docs/test-data/sim-e2e-bootstrap.sql` | [test-data/sim/sim-e2e-bootstrap.sql](../../test-data/sim/sim-e2e-bootstrap.sql) |
| `docs/test-data/sim-import-tenants-verify.sql` | [test-data/sim/sim-import-tenants-verify.sql](../../test-data/sim/sim-import-tenants-verify.sql) |
| `docs/test-data/sim-stage2b-import-fixtures.sql` | [test-data/sim/sim-stage2b-import-fixtures.sql](../../test-data/sim/sim-stage2b-import-fixtures.sql) |
| `docs/test-data/sim-stage2c-import-matrix-business.sql` | [test-data/sim/sim-stage2c-import-matrix-business.sql](../../test-data/sim/sim-stage2c-import-matrix-business.sql) |
| `docs/test-data/sim-stage2c-import-matrix-fixtures.sql` | [test-data/sim/sim-stage2c-import-matrix-fixtures.sql](../../test-data/sim/sim-stage2c-import-matrix-fixtures.sql) |
| `docs/test-data/sim-stage2c-import-matrix-stale.sql` | [test-data/sim/sim-stage2c-import-matrix-stale.sql](../../test-data/sim/sim-stage2c-import-matrix-stale.sql) |
| `docs/test-data/sim-stage2d-reset-errors.sql` | [test-data/sim/sim-stage2d-reset-errors.sql](../../test-data/sim/sim-stage2d-reset-errors.sql) |
| `docs/test-data/sim-stage2e-import-checkpoint-business.sql` | [test-data/sim/sim-stage2e-import-checkpoint-business.sql](../../test-data/sim/sim-stage2e-import-checkpoint-business.sql) |
| `docs/test-data/sim-stage2e-import-checkpoint.sql` | [test-data/sim/sim-stage2e-import-checkpoint.sql](../../test-data/sim/sim-stage2e-import-checkpoint.sql) |
| `docs/test-data/sim-stage3-export-source.sql` | [test-data/sim/sim-stage3-export-source.sql](../../test-data/sim/sim-stage3-export-source.sql) |
| `docs/test-data/sim-stage3b-export-fixtures.sql` | [test-data/sim/sim-stage3b-export-fixtures.sql](../../test-data/sim/sim-stage3b-export-fixtures.sql) |
| `docs/test-data/sim-stage3b-export-source.sql` | [test-data/sim/sim-stage3b-export-source.sql](../../test-data/sim/sim-stage3b-export-source.sql) |
| `docs/test-data/sim-stage3c-export-source.sql` | [test-data/sim/sim-stage3c-export-source.sql](../../test-data/sim/sim-stage3c-export-source.sql) |
| `docs/test-data/sim-stage4-process-business-fixtures.sql` | [test-data/sim/sim-stage4-process-business-fixtures.sql](../../test-data/sim/sim-stage4-process-business-fixtures.sql) |
| `docs/test-data/sim-stage4-process-platform-fixtures.sql` | [test-data/sim/sim-stage4-process-platform-fixtures.sql](../../test-data/sim/sim-stage4-process-platform-fixtures.sql) |
| `docs/test-data/sim-stage4b-process-source-v1.sql` | [test-data/sim/sim-stage4b-process-source-v1.sql](../../test-data/sim/sim-stage4b-process-source-v1.sql) |
| `docs/test-data/sim-stage4b-process-source-v2.sql` | [test-data/sim/sim-stage4b-process-source-v2.sql](../../test-data/sim/sim-stage4b-process-source-v2.sql) |
| `docs/test-data/sim-stage4b-process-stale-staging.sql` | [test-data/sim/sim-stage4b-process-stale-staging.sql](../../test-data/sim/sim-stage4b-process-stale-staging.sql) |
| `docs/test-data/sim-stage4c-process-business.sql` | [test-data/sim/sim-stage4c-process-business.sql](../../test-data/sim/sim-stage4c-process-business.sql) |
| `docs/test-data/sim-stage4c-process-platform.sql` | [test-data/sim/sim-stage4c-process-platform.sql](../../test-data/sim/sim-stage4c-process-platform.sql) |
| `docs/test-data/sim-stage5-dispatch-file.sql` | [test-data/sim/sim-stage5-dispatch-file.sql](../../test-data/sim/sim-stage5-dispatch-file.sql) |
| `docs/test-data/sim-stage5b-dispatch-fixtures.sql` | [test-data/sim/sim-stage5b-dispatch-fixtures.sql](../../test-data/sim/sim-stage5b-dispatch-fixtures.sql) |
| `docs/test-data/sim-stage5c-atomic-params.json` | [test-data/sim/sim-stage5c-atomic-params.json](../../test-data/sim/sim-stage5c-atomic-params.json) |
| `docs/test-data/sim-stage5c-dispatch-channels-platform.sql` | [test-data/sim/sim-stage5c-dispatch-channels-platform.sql](../../test-data/sim/sim-stage5c-dispatch-channels-platform.sql) |
| `docs/test-data/sim-stage5c-dispatch-file.sql` | [test-data/sim/sim-stage5c-dispatch-file.sql](../../test-data/sim/sim-stage5c-dispatch-file.sql) |
| `docs/test-data/sim-stage6c-trigger-fixtures.sql` | [test-data/sim/sim-stage6c-trigger-fixtures.sql](../../test-data/sim/sim-stage6c-trigger-fixtures.sql) |
| `docs/test-data/sim-stage6d-trigger-fixtures.sql` | [test-data/sim/sim-stage6d-trigger-fixtures.sql](../../test-data/sim/sim-stage6d-trigger-fixtures.sql) |
| `docs/test-data/sim-stage6d-trigger-outbox-retry.sql` | [test-data/sim/sim-stage6d-trigger-outbox-retry.sql](../../test-data/sim/sim-stage6d-trigger-outbox-retry.sql) |
| `docs/test-data/sim-stage6d-trigger-pause.sql` | [test-data/sim/sim-stage6d-trigger-pause.sql](../../test-data/sim/sim-stage6d-trigger-pause.sql) |
| `docs/test-data/sim-stage6d-trigger-resume.sql` | [test-data/sim/sim-stage6d-trigger-resume.sql](../../test-data/sim/sim-stage6d-trigger-resume.sql) |
| `docs/test-data/sim06-sdk-dispatch-echo-fixtures.sql` | [test-data/sim/sim06-sdk-dispatch-echo-fixtures.sql](../../test-data/sim/sim06-sdk-dispatch-echo-fixtures.sql) |
| `docs/test-data/test-full-coverage-import-suite/README.md` | [test-data/tenant-config-packages/README.md](../../test-data/tenant-config-packages/README.md) |
| `docs/test-data/test-full-coverage-import-suite/default-tenant-config-package-test.xlsx` | [test-data/tenant-config-packages/default-tenant-config-package-test.xlsx](../../test-data/tenant-config-packages/default-tenant-config-package-test.xlsx) |
| `docs/test-data/test-full-coverage-import-suite/ta-tenant-config-package-test.xlsx` | [test-data/tenant-config-packages/ta-tenant-config-package-test.xlsx](../../test-data/tenant-config-packages/ta-tenant-config-package-test.xlsx) |
| `docs/test-data/test-full-coverage-import-suite/tb-tenant-config-package-test.xlsx` | [test-data/tenant-config-packages/tb-tenant-config-package-test.xlsx](../../test-data/tenant-config-packages/tb-tenant-config-package-test.xlsx) |
| `docs/test-data/test-full-coverage-import-suite/tc-tenant-config-package-test.xlsx` | [test-data/tenant-config-packages/tc-tenant-config-package-test.xlsx](../../test-data/tenant-config-packages/tc-tenant-config-package-test.xlsx) |
| `docs/testing/coverage-gap-analysis.md` | [docs/testing/full-project-test-plan.md](../testing/full-project-test-plan.md) |
| `docs/testing/load-test-report.md` | [docs/verifications/load-test-report-2026-04-25.md](../verifications/load-test-report-2026-04-25.md) |
| `docs/testing/phase-coverage.md` | [docs/testing/full-project-test-plan.md](../testing/full-project-test-plan.md) |
| `docs/verifications/sonar-report-2026-08-05.csv` | [docs/archive/audit/sonar-full-scan-2026-08-05.csv](../archive/audit/sonar-full-scan-2026-08-05.csv) |
| `docs/verifications/sonar-report-2026-08-05.md` | [docs/archive/audit/sonar-full-scan-2026-08-05.md](../archive/audit/sonar-full-scan-2026-08-05.md) |
| `docs/verifications/sonar-report-2026-08-06.md` | [docs/archive/verifications/batch-4-ci-coverage-validation-2026-08-06.md](../archive/verifications/batch-4-ci-coverage-validation-2026-08-06.md) |
| `docs/verifications/sonar-report-2026-10-01.md` | [docs/audit/sonar-full-scan-and-remediation-2026-10-01.md](sonar-full-scan-and-remediation-2026-10-01.md) |
| `docs/verifications/worker-local-runtime-performance-2026-09-14.md` | [docs/verifications/worker-stress-and-recovery-validation-2026-09-14.md](../verifications/worker-stress-and-recovery-validation-2026-09-14.md) |
| `docs/verifications/worker-local-runtime-performance-2026-09-20.md` | [docs/verifications/worker-local-acceptance-and-mixed-load-2026-09-20.md](../verifications/worker-local-acceptance-and-mixed-load-2026-09-20.md) |


后续脚本目录、中文说明、CI总索引和机器基线整理见[同日脚本及CI治理记录](script-and-ci-governance-review-2026-10-24.md)，包含连续十项要求的逐项处理、最终566项脚本自测与未关闭边界。

## 2026-10-24 非 ADR 文件名与引用收敛

ADR 目录之外的执行和证据文件统一采用业务用途名称；此前映射表涉及编号支持材料的来源列改为历史业务主题标识，目标列保留真实当前链接。正式 ADR 文件和正常决策引用保持原命名，命名检查中的非法反例用于验证拒绝行为。

- 本地负载入口：`scripts/local/batch-consume-load.sh`，同步调用、用法、配套 SQL 索引和传输回归；请求前缀及临时响应文件同样使用用途名称。
- 文件束迁移：[V183](../../db/migration/V183__file_bundle_foundation.sql)、[V184](../../db/migration/V184__file_bundle_export_dispatch.sql)，版本和 SQL 字节不变，仅用于空库重新部署。危险操作预算只移动对应两个文件的键，各1项，不扩大计数。
- 四份验证日志改为 `logs/verification/workflow/replay-range-*.log`，逐文件 SHA-256 保持一致，已核对无打开句柄；执行日志的证据路径同步更新。
- 修复能力范围、坏数据隔离等已按业务主题迁移材料的旧路径。源码和日志的原有内容及并行改动保留，未执行数据库操作或真实负载验收。

本轮验证：7项脚本传输/文档命名回归通过；Shell语法与ShellCheck、脚本治理、文档日期和仓库卫生检查通过；两份迁移经Squawk检查0问题，源版本唯一且SQL/日志逐项哈希不变。活跃模块的18份编译迁移资源同步用途名称；运行JAR仍须从源码clean重建。临时验证快照保留其当时的文件布局，作为冻结的历史复现资产。

全仓文档结构检查受非本次改动的 `docs/architecture/architecture-truth.md` 所引缺失资产审计报告阻断；高风险操作全仓检查仍有既有V299等迁移和清理脚本预算差异。本次两个迁移仅移动原有预算键，各1项。未执行真实数据库迁移或负载验收。

## 2026-10-25 剩余归档全量核对

> 下述删除结果是初次处理记录，已被文末“历史原文恢复与方法提炼纠偏”撤回；最终保留状态以处置CSV和恢复记录为准。

基线为 `fix/wdi-current`、HEAD `f3d5e400d` 的本地工作树，包含已有暂存和未暂存改动；不修改冻结的 Workflow 工作树或 main。范围是本轮开始时剩余149个文件：140篇正文、1份原始CSV、8份目录索引。

逐文件核对正文主题、关键结论、未完项、证据和实际消费者，再与现行文档及必要源码交叉核对。裁定为110篇删除并承接、31份保留原始证据；8份索引中4份随空分类删除、4份按保留内容更新。另移除 `docs/stats/archive/README.md` 的空兼容索引。原路径、SHA-256、理由和目标逐项登记在[处置清单](archive-document-disposition-2026-10-25.csv)，不按文件名或命中数量冒充内容判断。

有效内容更新到既有治理规范、文档路由、漂移守卫总账、代码规约、API协议、ADR-046、Worker与大文件设计、日志设计、数据库/分区/容量/脚本Runbook、测试方案和E2E矩阵。十二份旧代码量快照的原数字与不同口径汇入当前统计说明；旧单机调优手册的trace/测量表汇入原日期的大文件验证报告。

源码核对纠正三点：Worker当前只批量CLAIM、执行与REPORT仍逐任务；服务端report-batch使用逐项独立事务，没有采用旧savepoint建议；服务端`enabled/jobOverrides`未被热路径调用，不能作为端点关闭手段。最后一项以`DOC-BATCH-FLAG-1`进入当前TODO，本轮仅修正文档/源码说明，不改变行为。

保留的原始Sonar CSV包含2840条历史记录，字节内容保持；迁移SQL及其校验和保持。历史问题没有在本轮逐条复现或重新跑测试，删除旧清单不代表原缺陷全部关闭。仅执行以下静态检查，不编译、不运行应用测试。归档从149个文件缩减为35个（31份原始证据、4份导航索引），本轮没有将文件移入其他归档目录。

### 本轮静态验证结果

| 实际命令 | 结果 |
|---|---|
| `python3 scripts/ci/check-docs-structure.py` | 首轮发现已删除分类的目录链接及测量表搬入后相对路径共2处错误；修正后完整复跑通过 |
| `python3 scripts/ci/check-code-doc-references.py` | 通过，源码与文档路径引用有效 |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过，dated=177、undated=274、current-dated-legacy=0 |
| `python3 scripts/ci/check-changelog-sync.py` | 通过 |
| `python3 scripts/ci/check-destructive-ops-governance.py` | 通过，663项/252个路径类别，未增长；本轮仅删除2条已删文档对应的预算 |
| `python3 scripts/ci/check-governance-baseline-expansion.py --base-ref HEAD` | 通过，未发现未审批扩张 |
| `bash -n scripts/ci/check-no-enable-preview.sh` | 语法通过；只更新文档来源说明和失败提示中的文档路径 |
| `git diff --check`、`git diff --cached --check` | 通过 |

另逐项校验处置CSV的149条记录、原文件哈希、最终存在性及承接目标；9个Java文件相对本轮前内容的去注释词法序列相同。原始Sonar CSV字节哈希不变，Flyway SQL未修改。HEAD仍为`f3d5e400d`，未提交，未更改暂存区。

未重新执行历史报告中的扫描、单测、真实数据库、负载或目标环境验收；服务端未生效灰度字段仍按`DOC-BATCH-FLAG-1`待后续实现决策和验证，不用文档修正冒充运行行为修复。

## 2026-10-25 历史原文恢复与方法提炼纠偏

用户明确指出历史经验与原始证据不能被规则摘要替代。上节删除正文的决定过于激进：即使规则已写回现行文档，原始排障、失败尝试、原测量和弃选理由仍有价值；原删除决定已撤回，不再作为最终处置状态。

范围逐篇去除改名/旧路径的重复计数后为120篇正文：上轮110篇来自删除前快照，前一批10篇从固定HEAD `f3d5e400d` 的可追溯版本恢复（这10篇不宣称与删除瞬间未提交内容字节相同）。全部在 `fix/wdi-current` 原路径恢复，增加历史适用性说明；必要时修复链接，不重写原问题状态或执行结论。恢复7个分类索引，历史目录最终162个文件。此前现行文档的有效更新继续保留，空统计兼容索引无需恢复。

提炼方法进入8个领域参考页和对应技能入口，文档技能补充保留/提炼流程。方法使用触发条件、调查步骤、反例/误判边界与验证方式，覆盖历史结论复核、方案取舍、修复复发、配置实际消费、可读性语义、事务/RLS、批量逐项身份、文件恢复、坏数据重试、SDK出站序列化和生命周期、数据库约束/索引、资源/读取放大/负载、测试时序、CI选测和前端异步目标/图编辑往返/可访问性。原文、现行规范和技能分别维护；不以新增技能删除证据。

逐篇原路径、恢复来源、SHA-256、行数、方法ID、技能去向及不强造技能的理由见[120篇提炼映射](archive-experience-skill-extraction-2026-10-25.csv)。十二份统计保留原数据并合并口径方法；未填验收模板明确没有真实验收结果。旧报告中的“唯一键缺tenant列必然越租”、Outbox重复就算缺陷、固定UUID策略、容器初始化推测和已撤销Wheel/编辑器方案均不直接升级为当前规则。

实际验证：

| 命令或核对 | 结果 |
|---|---|
| `python3 "$CODEX_HOME/skills/.system/skill-creator/scripts/quick_validate.py" .agents/skills/<skill>` | 对本轮9个修改技能分别执行，全部通过；8个新增参考页由技能入口按需链接 |
| `python3 scripts/ci/check-docs-structure.py` | 通过 |
| `python3 scripts/ci/check-code-doc-references.py` | 通过 |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过，dated=298、undated=281、current-dated-legacy=0 |
| `python3 scripts/ci/check-changelog-sync.py` | 通过 |
| `python3 scripts/ci/check-destructive-ops-governance.py` | 通过，663项/252个路径类别，未增长 |
| `python3 scripts/ci/check-governance-baseline-expansion.py --base-ref HEAD` | 通过 |
| `git diff --check`、`git diff --cached --check` | 通过 |
| 来源/正文/方法映射逐项核对 | 120篇恢复正文扣除新增历史适用性说明后的字节SHA-256与所记来源一致；120条映射唯一，全部技能入口及方法锚点有效；危险操作预算完整内容与删除前相同 |

本轮只修改历史原文、索引、技能和治理记录，未编译、未运行应用测试、历史扫描或故障演练。技能格式与静态引用检查不证明所有历史问题已复验，也不证明新方法已在真实任务中完成行为验证。未修改HEAD或暂存区，未修改main或冻结工作树。
