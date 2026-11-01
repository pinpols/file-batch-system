# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `c1f0e799b` + 当前工作区改动。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 6,204 | 796,700 | 416,617 | 52.3% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| test | 1,833 | 392,658 | 210,507 | 53.6% |
| prod | 3,472 | 321,185 | 153,391 | 47.8% |
| script | 721 | 64,045 | 40,345 | 63.0% |
| config | 68 | 10,269 | 7,678 | 74.8% |
| infra-config | 34 | 6,107 | 4,465 | 73.1% |
| sql | 76 | 2,436 | 231 | 9.5% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 4,675 | 622,442 | 328,642 | 52.8% |
| Shell | 217 | 35,828 | 28,335 | 79.1% |
| Python | 329 | 48,659 | 26,208 | 53.9% |
| YAML | 109 | 14,938 | 11,585 | 77.6% |
| XML | 222 | 31,038 | 9,283 | 29.9% |
| TypeScript | 38 | 6,824 | 3,259 | 47.8% |
| Rust | 24 | 8,967 | 3,074 | 34.3% |
| Properties | 5 | 3,177 | 2,637 | 83.0% |
| SQL | 542 | 16,996 | 1,979 | 11.6% |
| Go | 35 | 7,479 | 1,384 | 18.5% |
| TOML | 8 | 352 | 231 | 65.6% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java` | test | Java | 7,807 | 3,270 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobNodeDispatchIntegrationTest.java` | test | Java | 6,064 | 2,617 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java` | test | Java | 4,925 | 2,583 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayMetricsMapperIntegrationTest.java` | test | Java | 3,697 | 1,767 |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerServiceIntegrationTest.java` | test | Java | 2,798 | 1,459 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SelfServiceJobCompensationHttpE2eIT.java` | test | Java | 2,916 | 1,389 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,635 | 1,373 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,635 | 1,373 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,578 | 1,318 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,576 | 1,318 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java` | test | Java | 2,929 | 1,303 |
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,409 | 1,293 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | test | Java | 2,152 | 1,125 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java` | test | Java | 2,399 | 1,034 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/StaleWorkerReportFenceE2eIT.java` | test | Java | 2,040 | 1,014 |
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
