# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `421686d90`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,926 | 527,947 | 272,294 | 51.6% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,804 | 258,396 | 118,456 | 45.8% |
| test | 1,298 | 196,243 | 107,298 | 54.7% |
| script | 661 | 55,997 | 35,340 | 63.1% |
| config | 65 | 9,217 | 6,801 | 73.8% |
| infra-config | 34 | 5,816 | 4,227 | 72.7% |
| sql | 64 | 2,278 | 172 | 7.6% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,608 | 384,837 | 198,721 | 51.6% |
| Shell | 210 | 32,108 | 25,289 | 78.8% |
| Python | 262 | 37,474 | 19,343 | 51.6% |
| YAML | 108 | 13,776 | 10,556 | 76.6% |
| XML | 184 | 22,294 | 6,961 | 31.2% |
| TypeScript | 37 | 6,667 | 3,141 | 47.1% |
| Rust | 23 | 8,499 | 2,856 | 33.6% |
| Properties | 5 | 2,933 | 2,407 | 82.1% |
| SQL | 447 | 11,976 | 1,498 | 12.5% |
| Go | 34 | 7,049 | 1,302 | 18.5% |
| TOML | 8 | 334 | 220 | 65.9% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,271 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,456 | 1,203 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,454 | 1,203 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,386 | 1,170 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,386 | 1,170 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java` | test | Java | 2,051 | 875 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 879 | 796 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 1,017 | 734 |
| `deploy/docker/compose/app.yml` | config | YAML | 735 | 652 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,026 | 621 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 700 | 580 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java` | prod | Java | 949 | 568 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 769 | 561 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 985 | 559 |
| `scripts/local/start-all.sh` | script | Shell | 645 | 518 |
| `pom.xml` | config | XML | 845 | 512 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 814 | 503 |
| `scripts/local/sim-harness.sh` | script | Shell | 641 | 498 |
| `scripts/dev/sonar-scan.sh` | script | Shell | 577 | 482 |
| `scripts/local/be-acceptance.sh` | script | Shell | 615 | 482 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
