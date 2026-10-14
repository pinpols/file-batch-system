# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `10afb2bca`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 5,008 | 539,590 | 278,914 | 51.7% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,830 | 261,271 | 119,822 | 45.9% |
| test | 1,344 | 203,402 | 111,382 | 54.8% |
| script | 671 | 57,529 | 36,448 | 63.4% |
| config | 65 | 9,265 | 6,841 | 73.8% |
| infra-config | 34 | 5,845 | 4,249 | 72.7% |
| sql | 64 | 2,278 | 172 | 7.6% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,674 | 393,864 | 203,600 | 51.7% |
| Shell | 215 | 32,939 | 26,007 | 79.0% |
| Python | 269 | 39,006 | 20,246 | 51.9% |
| YAML | 108 | 13,862 | 10,627 | 76.7% |
| XML | 186 | 22,424 | 7,001 | 31.2% |
| TypeScript | 37 | 6,667 | 3,141 | 47.1% |
| Rust | 23 | 8,499 | 2,856 | 33.6% |
| Properties | 5 | 2,943 | 2,417 | 82.1% |
| SQL | 449 | 12,003 | 1,497 | 12.5% |
| Go | 34 | 7,049 | 1,302 | 18.5% |
| TOML | 8 | 334 | 220 | 65.9% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,271 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,461 | 1,208 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,459 | 1,208 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,415 | 1,192 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,415 | 1,192 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java` | test | Java | 2,204 | 939 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 879 | 796 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 1,017 | 734 |
| `deploy/docker/compose/app.yml` | config | YAML | 735 | 652 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,026 | 621 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 699 | 582 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java` | prod | Java | 976 | 569 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 769 | 561 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 985 | 559 |
| `scripts/local/start-all.sh` | script | Shell | 645 | 518 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 824 | 513 |
| `pom.xml` | config | XML | 845 | 512 |
| `scripts/dev/sonar-scan.sh` | script | Shell | 594 | 499 |
| `scripts/local/sim-harness.sh` | script | Shell | 641 | 498 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | prod | Java | 1,019 | 497 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
