# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `881be7a34`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 6,090 | 785,241 | 409,776 | 52.2% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| test | 1,789 | 384,259 | 205,332 | 53.4% |
| prod | 3,405 | 318,580 | 151,929 | 47.7% |
| script | 718 | 63,600 | 40,151 | 63.1% |
| config | 68 | 10,259 | 7,668 | 74.7% |
| infra-config | 34 | 6,107 | 4,465 | 73.1% |
| sql | 76 | 2,436 | 231 | 9.5% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 4,568 | 611,859 | 322,211 | 52.7% |
| Shell | 217 | 35,784 | 28,295 | 79.1% |
| Python | 328 | 48,409 | 26,031 | 53.8% |
| YAML | 109 | 14,929 | 11,576 | 77.5% |
| XML | 219 | 30,829 | 9,210 | 29.9% |
| TypeScript | 38 | 6,824 | 3,259 | 47.8% |
| Rust | 24 | 8,967 | 3,074 | 34.3% |
| Properties | 5 | 3,173 | 2,633 | 83.0% |
| SQL | 539 | 16,636 | 1,872 | 11.3% |
| Go | 35 | 7,479 | 1,384 | 18.5% |
| TOML | 8 | 352 | 231 | 65.6% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java` | test | Java | 7,773 | 3,260 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobNodeDispatchIntegrationTest.java` | test | Java | 6,048 | 2,605 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java` | test | Java | 4,875 | 2,567 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayMetricsMapperIntegrationTest.java` | test | Java | 3,697 | 1,767 |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerServiceIntegrationTest.java` | test | Java | 2,772 | 1,457 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SelfServiceJobCompensationHttpE2eIT.java` | test | Java | 2,914 | 1,388 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,635 | 1,373 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,635 | 1,373 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,576 | 1,316 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,574 | 1,316 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java` | test | Java | 2,929 | 1,303 |
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,409 | 1,293 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | test | Java | 2,152 | 1,125 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java` | test | Java | 2,399 | 1,034 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/StaleWorkerReportFenceE2eIT.java` | test | Java | 2,039 | 1,013 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBatchInitStrictAtomicityIntegrationTest.java` | test | Java | 1,570 | 951 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigDependencyRlsIntegrationTest.java` | test | Java | 1,854 | 929 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ArchiveColdStorageIntegrationTest.java` | test | Java | 1,887 | 917 |
| `scripts/local/sim-harness.sh` | script | Shell | 1,092 | 908 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/BatchDayCatchUpOperationSchemaIntegrationTest.java` | test | Java | 1,577 | 896 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
