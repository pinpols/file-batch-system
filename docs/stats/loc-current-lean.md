# 精简代码量统计 — 2026-09-25

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `fe29fbffb` + 当前工作区改动。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,530 | 466,106 | 234,989 | 50.4% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,640 | 240,838 | 109,900 | 45.6% |
| test | 1,158 | 166,590 | 88,573 | 53.2% |
| script | 589 | 43,386 | 27,035 | 62.3% |
| config | 52 | 7,873 | 5,550 | 70.5% |
| infra-config | 32 | 5,191 | 3,766 | 72.5% |
| sql | 59 | 2,228 | 165 | 7.4% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,352 | 342,951 | 173,868 | 50.7% |
| Shell | 181 | 27,199 | 20,957 | 77.1% |
| Python | 202 | 26,940 | 13,566 | 50.4% |
| YAML | 95 | 12,044 | 9,004 | 74.8% |
| XML | 169 | 20,606 | 6,372 | 30.9% |
| TypeScript | 37 | 6,573 | 3,097 | 47.1% |
| Rust | 22 | 8,456 | 2,856 | 33.8% |
| Properties | 5 | 2,861 | 2,335 | 81.6% |
| SQL | 426 | 11,191 | 1,439 | 12.9% |
| Go | 34 | 6,955 | 1,281 | 18.4% |
| TOML | 7 | 330 | 214 | 64.8% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,264 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,420 | 1,167 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,418 | 1,167 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,161 | 966 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,161 | 966 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 792 | 714 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 947 | 668 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,106 | 656 |
| `deploy/docker/compose/app.yml` | config | YAML | 683 | 602 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 768 | 560 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 979 | 559 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 647 | 528 |
| `scripts/local/be-acceptance.sh` | script | Shell | 612 | 479 |
| `scripts/local/start-all.sh` | script | Shell | 605 | 478 |
| `pom.xml` | config | XML | 763 | 445 |
| `scripts/local/sim-harness.sh` | script | Shell | 554 | 417 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java` | prod | Java | 841 | 399 |
| `scripts/dev/sonar-scan.sh` | script | Shell | 488 | 397 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationServiceTest.java` | test | Java | 577 | 395 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceServiceTest.java` | test | Java | 745 | 394 |

## 复跑

```bash
python3 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
