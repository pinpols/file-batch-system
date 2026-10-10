# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `2e4b528dd` + 当前工作区改动。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,903 | 519,053 | 268,070 | 51.6% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,796 | 255,417 | 116,988 | 45.8% |
| test | 1,284 | 190,566 | 104,690 | 54.9% |
| script | 660 | 55,767 | 35,200 | 63.1% |
| config | 65 | 9,209 | 6,793 | 73.8% |
| infra-config | 34 | 5,816 | 4,227 | 72.7% |
| sql | 64 | 2,278 | 172 | 7.6% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,589 | 376,751 | 194,870 | 51.7% |
| Shell | 210 | 32,056 | 25,242 | 78.7% |
| Python | 259 | 36,867 | 19,087 | 51.8% |
| YAML | 108 | 13,768 | 10,548 | 76.6% |
| XML | 183 | 22,187 | 6,933 | 31.2% |
| TypeScript | 37 | 6,667 | 3,141 | 47.1% |
| Rust | 23 | 8,499 | 2,856 | 33.6% |
| Properties | 5 | 2,899 | 2,373 | 81.9% |
| SQL | 447 | 11,976 | 1,498 | 12.5% |
| Go | 34 | 7,049 | 1,302 | 18.5% |
| TOML | 8 | 334 | 220 | 65.9% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,271 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,439 | 1,186 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,437 | 1,186 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,386 | 1,170 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,386 | 1,170 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 879 | 796 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 1,017 | 734 |
| `deploy/docker/compose/app.yml` | config | YAML | 735 | 652 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,004 | 605 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 700 | 580 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 769 | 561 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 985 | 559 |
| `scripts/local/start-all.sh` | script | Shell | 645 | 518 |
| `pom.xml` | config | XML | 845 | 512 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 814 | 503 |
| `scripts/local/sim-harness.sh` | script | Shell | 641 | 498 |
| `scripts/local/be-acceptance.sh` | script | Shell | 615 | 482 |
| `scripts/dev/sonar-scan.sh` | script | Shell | 571 | 478 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | prod | Java | 975 | 469 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | prod | Java | 1,031 | 460 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
