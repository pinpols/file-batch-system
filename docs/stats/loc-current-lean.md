# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `23803bb83`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,555 | 469,281 | 236,818 | 50.5% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,654 | 242,283 | 110,649 | 45.7% |
| test | 1,165 | 167,452 | 89,015 | 53.2% |
| script | 593 | 44,192 | 27,612 | 62.5% |
| config | 52 | 7,933 | 5,609 | 70.7% |
| infra-config | 32 | 5,193 | 3,768 | 72.6% |
| sql | 59 | 2,228 | 165 | 7.4% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,372 | 345,196 | 175,023 | 50.7% |
| Shell | 182 | 27,548 | 21,280 | 77.2% |
| Python | 205 | 27,424 | 13,845 | 50.5% |
| YAML | 95 | 12,046 | 9,004 | 74.7% |
| XML | 169 | 20,668 | 6,432 | 31.1% |
| TypeScript | 37 | 6,573 | 3,097 | 47.1% |
| Rust | 22 | 8,456 | 2,856 | 33.8% |
| Properties | 5 | 2,871 | 2,345 | 81.7% |
| SQL | 427 | 11,213 | 1,440 | 12.8% |
| Go | 34 | 6,955 | 1,281 | 18.4% |
| TOML | 7 | 331 | 215 | 65.0% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,264 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,425 | 1,172 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,423 | 1,172 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,161 | 966 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,161 | 966 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 792 | 714 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 948 | 669 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,145 | 644 |
| `deploy/docker/compose/app.yml` | config | YAML | 684 | 603 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 768 | 560 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 979 | 559 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 647 | 528 |
| `scripts/local/start-all.sh` | script | Shell | 640 | 512 |
| `scripts/local/be-acceptance.sh` | script | Shell | 612 | 479 |
| `pom.xml` | config | XML | 761 | 453 |
| `scripts/local/pre-push-sdk-checks.sh` | script | Shell | 558 | 424 |
| `scripts/local/sim-harness.sh` | script | Shell | 554 | 417 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java` | prod | Java | 869 | 408 |
| `scripts/dev/sonar-scan.sh` | script | Shell | 488 | 397 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationServiceTest.java` | test | Java | 577 | 395 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
