# 文档分类与统一命名复核 — 2026-10-24

## 范围与结论

- 分支：`feature/workflow-daily-integration`；基线 HEAD：`ee01e9751`，包含当前已暂存、未暂存文档改动；本轮不变更业务行为。
- 范围：全部 Markdown 路径与文件名、当前目录文档页眉/性质声明、历史快照候选正文及受影响索引、角色路径、机器契约、Skill 和代码注释引用。
- 分类：架构、模型、原语、协议、操作方法和可复用清单属于持续维护资产，不带文件名日期；定期审计、问题修复、压测、验收与阶段方案属于增量记录，保留原基线日期。文件移动不构成新的实现或验收结论。
- 已纠正首轮误判：GitOps 接入未完成目标环境验收，仍应作为现行操作资产维护；字段映射设计仍是现行设计资产。上线审计报告与可复用审计检查清单分设入口。
- 旧成熟度、容量、时间轮、问题/修复、覆盖矩阵等移入历史分类；阶段方案不随推进日改名，ADR 保留编号。当前规模快照不加日期；历史 LOC、版本报告与归档采用原日期后缀。
- 归档统一英文短横线主题、版本在主题后、完整日期在文件名末尾；跨日记录保留完整起讫日期。历史结论保留，只修复引用和归属说明。
- `legacy-design-chapter-map.md` 为持续维护的资产导航，恢复到设计目录；已撤销时间轮入口和历史 POM 入口改为现行 Trigger/项目结构。

## 命名与迁移清单

复核时目录含 553 份 Markdown（包含本报告）；相对 HEAD 共调整 92 个原路径。清单用于目录追溯，当前事项仍以 [TODO Master](../analysis/todo-master.md) 为准。长期分类与命名资产见 [文档治理规范](../standards/document-governance.md)，本报告保存本轮增量变更。

| 原路径 | 现路径 |
|---|---|
| `docs/analysis/commit-review-2026-06-05-to-09.md` | [docs/review/commit-review-2026-06-05-to-2026-06-09.md](../review/commit-review-2026-06-05-to-2026-06-09.md) |
| `docs/analysis/deep-issue-analysis.md` | [docs/archive/analysis/deep-issue-analysis-2026-04-30.md](../archive/analysis/deep-issue-analysis-2026-04-30.md) |
| `docs/analysis/fix-report.md` | [docs/archive/analysis/fix-report-2026-04-21.md](../archive/analysis/fix-report-2026-04-21.md) |
| `docs/analysis/go-live-realism-audit-2026-06-21.md` | [docs/standards/go-live-audit-checklist.md](../standards/go-live-audit-checklist.md) |
| `docs/analysis/project-assessment.md` | [docs/archive/analysis/project-assessment-2026-04-30.md](../archive/analysis/project-assessment-2026-04-30.md) |
| `docs/architecture/deficiencies-2026-05-30.md` | [docs/archive/architecture/deficiencies-2026-05-30.md](../archive/architecture/deficiencies-2026-05-30.md) |
| `docs/architecture/maturity-assessment.md` | [docs/archive/architecture/maturity-assessment-2026-04-26.md](../archive/architecture/maturity-assessment-2026-04-26.md) |
| `docs/architecture/p0-p1-p2-roadmap.md` | [docs/archive/architecture/p0-p1-p2-roadmap-2026-05-29.md](../archive/architecture/p0-p1-p2-roadmap-2026-05-29.md) |
| `docs/architecture/quartz-replacement-design.md` | [docs/archive/architecture/quartz-replacement-design-2026-04-25.md](../archive/architecture/quartz-replacement-design-2026-04-25.md) |
| `docs/architecture/quartz-replacement-evaluation.md` | [docs/archive/architecture/quartz-replacement-evaluation-2026-04-25.md](../archive/architecture/quartz-replacement-evaluation-2026-04-25.md) |
| `docs/architecture/rework-classification.md` | [docs/archive/architecture/rework-classification-2026-04-25.md](../archive/architecture/rework-classification-2026-04-25.md) |
| `docs/architecture/runtime-compatibility-contract-2026-09-01.md` | [docs/architecture/runtime-compatibility-contract.md](../architecture/runtime-compatibility-contract.md) |
| `docs/architecture/scalability-assessment.md` | [docs/archive/architecture/scalability-assessment-2026-07-28.md](../archive/architecture/scalability-assessment-2026-07-28.md) |
| `docs/archive/analysis/2026-06-02-deep-review-round-2.md` | [docs/archive/analysis/deep-review-round-2-2026-06-02.md](../archive/analysis/deep-review-round-2-2026-06-02.md) |
| `docs/archive/analysis/2026-06-02-java-python-sdk-deep-review.md` | [docs/archive/analysis/java-python-sdk-deep-review-2026-06-02.md](../archive/analysis/java-python-sdk-deep-review-2026-06-02.md) |
| `docs/archive/analysis/2026-06-02-sdk-atomic-fe-deep-review.md` | [docs/archive/analysis/sdk-atomic-fe-deep-review-2026-06-02.md](../archive/analysis/sdk-atomic-fe-deep-review-2026-06-02.md) |
| `docs/archive/analysis/2026-06-02-sdk-code-deep-review.md` | [docs/archive/analysis/sdk-code-deep-review-2026-06-02.md](../archive/analysis/sdk-code-deep-review-2026-06-02.md) |
| `docs/archive/analysis/2026-06-02-sdk-fix-execution-plan.md` | [docs/archive/analysis/sdk-fix-execution-plan-2026-06-02.md](../archive/analysis/sdk-fix-execution-plan-2026-06-02.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-arch-tx-tenant.md` | [docs/archive/analysis/deep-scan-be-arch-tx-tenant-2026-06-03.md](../archive/analysis/deep-scan-be-arch-tx-tenant-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-architecture-v2.md` | [docs/archive/analysis/deep-scan-be-architecture-v2-2026-06-03.md](../archive/analysis/deep-scan-be-architecture-v2-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-architecture.md` | [docs/archive/analysis/deep-scan-be-architecture-2026-06-03.md](../archive/analysis/deep-scan-be-architecture-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-business-ops.md` | [docs/archive/analysis/deep-scan-be-business-ops-2026-06-03.md](../archive/analysis/deep-scan-be-business-ops-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-resources-scheduling-v2.md` | [docs/archive/analysis/deep-scan-be-resources-scheduling-v2-2026-06-03.md](../archive/analysis/deep-scan-be-resources-scheduling-v2-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-resources-scheduling.md` | [docs/archive/analysis/deep-scan-be-resources-scheduling-2026-06-03.md](../archive/analysis/deep-scan-be-resources-scheduling-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-be-security.md` | [docs/archive/analysis/deep-scan-be-security-2026-06-03.md](../archive/analysis/deep-scan-be-security-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-cicd-ops.md` | [docs/archive/analysis/deep-scan-cicd-ops-2026-06-03.md](../archive/analysis/deep-scan-cicd-ops-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-feedback.md` | [docs/archive/analysis/deep-scan-fe-feedback-2026-06-03.md](../archive/analysis/deep-scan-fe-feedback-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-interaction.md` | [docs/archive/analysis/deep-scan-fe-interaction-2026-06-03.md](../archive/analysis/deep-scan-fe-interaction-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-layout-responsive-v2.md` | [docs/archive/analysis/deep-scan-fe-layout-responsive-v2-2026-06-03.md](../archive/analysis/deep-scan-fe-layout-responsive-v2-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-layout-responsive.md` | [docs/archive/analysis/deep-scan-fe-layout-responsive-2026-06-03.md](../archive/analysis/deep-scan-fe-layout-responsive-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-theme-color-a11y-v2.md` | [docs/archive/analysis/deep-scan-fe-theme-color-a11y-v2-2026-06-03.md](../archive/analysis/deep-scan-fe-theme-color-a11y-v2-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-theme-color-a11y.md` | [docs/archive/analysis/deep-scan-fe-theme-color-a11y-2026-06-03.md](../archive/analysis/deep-scan-fe-theme-color-a11y-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-fe-usability.md` | [docs/archive/analysis/deep-scan-fe-usability-2026-06-03.md](../archive/analysis/deep-scan-fe-usability-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-sdk-data-integrity.md` | [docs/archive/analysis/deep-scan-sdk-data-integrity-2026-06-03.md](../archive/analysis/deep-scan-sdk-data-integrity-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-deep-scan-summary.md` | [docs/archive/analysis/deep-scan-summary-2026-06-03.md](../archive/analysis/deep-scan-summary-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-p2-items-evaluation.md` | [docs/archive/analysis/p2-items-evaluation-2026-06-03.md](../archive/analysis/p2-items-evaluation-2026-06-03.md) |
| `docs/archive/analysis/2026-06-03-sdk-test-parity-gap.md` | [docs/archive/analysis/sdk-test-parity-gap-2026-06-03.md](../archive/analysis/sdk-test-parity-gap-2026-06-03.md) |
| `docs/archive/analysis/2026-06-10-full-audit.md` | [docs/archive/analysis/full-audit-2026-06-10.md](../archive/analysis/full-audit-2026-06-10.md) |
| `docs/archive/analysis/audit-campaign-2026-05-15-complete.md` | [docs/archive/analysis/audit-campaign-complete-2026-05-15.md](../archive/analysis/audit-campaign-complete-2026-05-15.md) |
| `docs/archive/analysis/deep-issue-analysis-v1.md` | [docs/archive/analysis/deep-issue-analysis-v1-2026-04-15.md](../archive/analysis/deep-issue-analysis-v1-2026-04-15.md) |
| `docs/archive/analysis/deep-issue-analysis-v2.md` | [docs/archive/analysis/deep-issue-analysis-v2-2026-04-15.md](../archive/analysis/deep-issue-analysis-v2-2026-04-15.md) |
| `docs/archive/analysis/deep-issue-analysis-v3.md` | [docs/archive/analysis/deep-issue-analysis-v3-2026-04-20.md](../archive/analysis/deep-issue-analysis-v3-2026-04-20.md) |
| `docs/archive/analysis/fix-report-v1.md` | [docs/archive/analysis/fix-report-v1-2026-04-15.md](../archive/analysis/fix-report-v1-2026-04-15.md) |
| `docs/archive/analysis/fix-report-v2.md` | [docs/archive/analysis/fix-report-v2-2026-04-15.md](../archive/analysis/fix-report-v2-2026-04-15.md) |
| `docs/archive/analysis/fix-report-v3.md` | [docs/archive/analysis/fix-report-v3-2026-04-20.md](../archive/analysis/fix-report-v3-2026-04-20.md) |
| `docs/archive/analysis/hardening-backlog-v3.md` | [docs/archive/analysis/hardening-backlog-v3-2026-04-20.md](../archive/analysis/hardening-backlog-v3-2026-04-20.md) |
| `docs/archive/analysis/hardening-backlog-v4.md` | [docs/archive/analysis/hardening-backlog-v4-2026-04-21.md](../archive/analysis/hardening-backlog-v4-2026-04-21.md) |
| `docs/archive/analysis/hardening-backlog-v6.md` | [docs/archive/analysis/hardening-backlog-v6-2026-06-15.md](../archive/analysis/hardening-backlog-v6-2026-06-15.md) |
| `docs/archive/analysis/positional-args-cleanup-plan.md` | [docs/archive/analysis/positional-args-cleanup-plan-2026-05-01.md](../archive/analysis/positional-args-cleanup-plan-2026-05-01.md) |
| `docs/archive/backlog/adr046-phase2-2.3-worker-batch-construction.md` | [docs/archive/backlog/adr-046-phase-2-worker-batch-construction-2026-06-23.md](../archive/backlog/adr-046-phase-2-worker-batch-construction-2026-06-23.md) |
| `docs/archive/design/mega-chapter-map.md` | [docs/design/legacy-design-chapter-map.md](../design/legacy-design-chapter-map.md) |
| `docs/archive/testing/historical-test-reports-2026-04-09-10.md` | [docs/archive/testing/historical-test-reports-2026-04-09-to-2026-04-10.md](../archive/testing/historical-test-reports-2026-04-09-to-2026-04-10.md) |
| `docs/audit/INDEX-2026-05-23.md` | [docs/audit/module-architecture-audit-index-2026-05-23.md](module-architecture-audit-index-2026-05-23.md) |
| `docs/backlog/outstanding-work-2026-06-23.md` | [docs/archive/backlog/outstanding-work-2026-06-23.md](../archive/backlog/outstanding-work-2026-06-23.md) |
| `docs/backlog/rest-command-api-toggle-governance-2026-09-26.md` | [docs/archive/backlog/rest-command-api-toggle-governance-2026-09-26.md](../archive/backlog/rest-command-api-toggle-governance-2026-09-26.md) |
| `docs/design/api-gap-analysis.md` | [docs/archive/analysis/api-gap-analysis-2026-04-10.md](../archive/analysis/api-gap-analysis-2026-04-10.md) |
| `docs/design/file-integrity-sidecar-manifest-design-2026-06-07.md` | [docs/design/file-integrity-sidecar-manifest-design.md](../design/file-integrity-sidecar-manifest-design.md) |
| `docs/design/import-export-column-mapping-default-inference-2026-06-20.md` | [docs/design/import-export-column-mapping-default-inference.md](../design/import-export-column-mapping-default-inference.md) |
| `docs/design/process-worker-known-issues.md` | [docs/archive/analysis/process-worker-known-issues-2026-04-28.md](../archive/analysis/process-worker-known-issues-2026-04-28.md) |
| `docs/design/project-structure-pom.md` | [docs/archive/design/project-structure-pom-2026-03-21.md](../archive/design/project-structure-pom-2026-03-21.md) |
| `docs/design/sdk-industry-benchmark.md` | [docs/archive/analysis/sdk-industry-benchmark-2026-05-31.md](../archive/analysis/sdk-industry-benchmark-2026-05-31.md) |
| `docs/plans/2026-06-06-export-partition-keyset-range.md` | [docs/plans/export-partition-keyset-range-2026-06-06.md](../plans/export-partition-keyset-range-2026-06-06.md) |
| `docs/plans/2026-06-06-export-partition-slice.md` | [docs/plans/export-partition-slice-2026-06-06.md](../plans/export-partition-slice-2026-06-06.md) |
| `docs/plans/2026-06-06-migrate-minio-sdk-to-aws-sdk-v2.md` | [docs/plans/migrate-minio-sdk-to-aws-sdk-v2-2026-06-06.md](../plans/migrate-minio-sdk-to-aws-sdk-v2-2026-06-06.md) |
| `docs/plans/2026-06-13-ha-deployment-plan.md` | [docs/plans/ha-deployment-plan-2026-06-13.md](../plans/ha-deployment-plan-2026-06-13.md) |
| `docs/plans/fe-worklist-2026-h2-atomic-sdk.md` | [docs/plans/fe-atomic-sdk-worklist-2026-h2.md](../plans/fe-atomic-sdk-worklist-2026-h2.md) |
| `docs/plans/sdk-roadmap-2026-h2-progress.md` | [docs/plans/sdk-roadmap-progress-2026-h2.md](../plans/sdk-roadmap-progress-2026-h2.md) |
| `docs/runbook/archive/index-consolidation-2026-05.md` | [docs/archive/runbook/index-consolidation-2026-05.md](../archive/runbook/index-consolidation-2026-05.md) |
| `docs/runbook/archive/partition-cutover-2026-05.md` | [docs/archive/runbook/partition-cutover-2026-05.md](../archive/runbook/partition-cutover-2026-05.md) |
| `docs/runbook/ci-cd-followup-2026-05-22.md` | [docs/archive/runbook/ci-cd-followup-2026-05-22.md](../archive/runbook/ci-cd-followup-2026-05-22.md) |
| `docs/runbook/ci-speedup-2026-06-02.md` | [docs/archive/runbook/ci-speedup-2026-06-02.md](../archive/runbook/ci-speedup-2026-06-02.md) |
| `docs/runbook/e2e-it-optimization-2026-05-22.md` | [docs/archive/testing/e2e-it-optimization-2026-05-22.md](../archive/testing/e2e-it-optimization-2026-05-22.md) |
| `docs/runbook/gitops-onboarding-2026-05-22.md` | [docs/runbook/gitops-onboarding.md](../runbook/gitops-onboarding.md) |
| `docs/runbook/go-live-realism-audit-2026-06-21.md` | [docs/standards/go-live-audit-checklist.md](../standards/go-live-audit-checklist.md) |
| `docs/runbook/minio-lifecycle-policy.md` | [docs/runbook/object-storage-lifecycle-policy.md](../runbook/object-storage-lifecycle-policy.md) |
| `docs/runbook/password-security-backlog.md` | [docs/archive/backlog/password-security-backlog-2026-05-23.md](../archive/backlog/password-security-backlog-2026-05-23.md) |
| `docs/runbook/sql-audit-2026-05-20.md` | [docs/archive/analysis/sql-audit-2026-05-20.md](../archive/analysis/sql-audit-2026-05-20.md) |
| `docs/runbook/tia-poc-2026-05-22.md` | [docs/archive/testing/tia-poc-2026-05-22.md](../archive/testing/tia-poc-2026-05-22.md) |
| `docs/runbook/worker-stage-coverage.md` | [docs/verifications/worker-stage-coverage-2026-04-25.md](../verifications/worker-stage-coverage-2026-04-25.md) |
| `docs/sdk/conformance-gap-analysis-2026-06-16.md` | [docs/archive/analysis/conformance-gap-analysis-2026-06-16.md](../archive/analysis/conformance-gap-analysis-2026-06-16.md) |
| `docs/spike/adr-038-p3-export-file-recovery.md` | [docs/spike/adr-038-p3-export-file-recovery-2026-06-05.md](../spike/adr-038-p3-export-file-recovery-2026-06-05.md) |
| `docs/spike/resilience4j-sb4-compat.md` | [docs/spike/resilience4j-sb4-compat-2026-09-25.md](../spike/resilience4j-sb4-compat-2026-09-25.md) |
| `docs/stats/archive/loc-2026-07-22-lean.md` | [docs/stats/archive/loc-lean-2026-07-22.md](../stats/archive/loc-lean-2026-07-22.md) |
| `docs/stats/archive/loc-2026-07-31-lean.md` | [docs/stats/archive/loc-lean-2026-07-31.md](../stats/archive/loc-lean-2026-07-31.md) |
| `docs/stats/archive/loc-2026-08-02-lean.md` | [docs/stats/archive/loc-lean-2026-08-02.md](../stats/archive/loc-lean-2026-08-02.md) |
| `docs/stats/archive/loc-2026-08-04-lean.md` | [docs/stats/archive/loc-lean-2026-08-04.md](../stats/archive/loc-lean-2026-08-04.md) |
| `docs/stats/archive/loc-2026-08-09-lean.md` | [docs/stats/archive/loc-lean-2026-08-09.md](../stats/archive/loc-lean-2026-08-09.md) |
| `docs/stats/archive/loc-2026-09-01-lean.md` | [docs/stats/archive/loc-lean-2026-09-01.md](../stats/archive/loc-lean-2026-09-01.md) |
| `docs/stats/archive/loc-2026-09-24-lean.md` | [docs/stats/archive/loc-lean-2026-09-24.md](../stats/archive/loc-lean-2026-09-24.md) |
| `docs/testing/coverage-gap-analysis.md` | [docs/archive/testing/coverage-gap-analysis-2026-04-08.md](../archive/testing/coverage-gap-analysis-2026-04-08.md) |
| `docs/testing/load-test-report.md` | [docs/verifications/load-test-report-2026-04-25.md](../verifications/load-test-report-2026-04-25.md) |
| `docs/testing/phase-coverage.md` | [docs/archive/testing/phase-coverage-2026-09-11.md](../archive/testing/phase-coverage-2026-09-11.md) |

## 验证与边界

静态复核命令与结果：

| 命令 | 结果 |
|---|---|
| `python3 scripts/ci/check-docs-structure.py` | 通过 |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过；现行带日期历史例外为 0 |
| `python3 scripts/ci/check-code-doc-references.py` | 通过 |
| `python3 scripts/ci/check-slo-sli-catalog.py` | 通过 |
| `python3 scripts/ci/check-sdk-runtime-alignment.py` | 通过 |
| `python3 scripts/ci/check-application-governance.py` | 通过；6 个 control |
| `bash scripts/ci/check-hardcoded-runtime-config.sh` | 通过；原本机路径用声明的变量表示，未重跑历史业务命令 |
| `git diff --check` | 通过 |

命名门禁 8 个临时正反例均符合预期：拦截缺日期归档、日期前缀、带日期长期资产和下划线文件名；放行无日期资产、日期报告、带版本日期的归档及半年计划。临时探针全部清理，未登记白名单放行。

验证覆盖命名、路径和引用，不代表每份历史报告已对当前代码重新验收；历史时间戳不更新为本次整理日期。数据库迁移中的历史审计来源兼容存根保留，防止改写已发布迁移脚本。
