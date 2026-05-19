# 前后端契约与文档整理记录 — 2026-05-19

## 背景

本记录来自前端仓库 `../batch-console` 与后端仓库 `file-batch-system` 的联合扫描。重点不是新增功能，而是整理当前文档与实现之间的偏差，给后续修复排优先级。

## 当前阻断项

### 1. Console OpenAPI 路径漂移

`python3 scripts/ci/check-console-openapi-paths.py` 当前失败：

```text
In Console*Controller but not in OpenAPI:
  GET /api/console/meta/pipeline-stages
  GET /api/console/meta/step-impls
```

代码入口：

- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleMetaController.java`
- 前端调用：`../batch-console/src/api/meta.ts`

处理要求：

1. 在 `docs/api/console-api.openapi.yaml` 补齐两个 path。
2. 在 `docs/api/console-api-protocol.md` changelog 追加说明。
3. 前端重新生成 `src/types/api.generated.ts`。

### 2. Job Bundle 事务语义与文档不一致

OpenAPI 对 `POST /api/console/jobs/bundle/create` 和 `POST /api/console/jobs/bundle/import` 的描述使用了 “one backend transaction”。当前配置初始化实现是单项失败隔离：

- `TenantConfigInitApplyHandlers.applySpecs()` 使用 nested transaction/savepoint，并 catch 单项异常。
- `DefaultConsoleTenantConfigInitApplicationServiceTest.batchInit_itemFailureTrackedInStatsBothTenantsStillSuccess` 明确断言失败项仍不影响 tenant success。

这对 Config Sync 的“批量尽力导入”是合理语义，但和 Job Bundle 的“作业及关联实体一次性建好”不是同一个承诺。

建议二选一：

- 若 Job Bundle 要严格原子性：为 Job Bundle 增加 strict/all-or-nothing 路径，任一关联实体失败则整体回滚。
- 若继续沿用部分成功：修改 OpenAPI 和 protocol，去掉 “one backend transaction” 的误导描述，并让前端明确展示部分成功/失败明细。

## 已确认非阻断但需跟进

| 问题 | 说明 |
|---|---|
| Console 幂等防重主链路 | 已有 `ConsoleIdempotencyInterceptor`、`@Idempotent`、前端自动注入 `Idempotency-Key`。Job Bundle Controller 也是类级 `@Idempotent` |
| 前端 Bundle import | 后端有 `/api/console/jobs/bundle/import`，前端当前只有 `createBundle/exportBundle` wrapper |
| 前端全量聚合查询 | 后端 OpenAPI 已给部分查询暴露分页/过滤，前端仍有多处 `fetchAllPageItems` |

## 验证命令

| 命令 | 结果 |
|---|---|
| `python3 scripts/ci/check-console-openapi-paths.py` | 失败，见上方两个 meta endpoint |
| `mvn -pl batch-console-api -am -DskipTests compile` | 通过 |
| `mvn -pl batch-console-api spotless:check -DskipTests` | 通过 |
| `mvn -pl batch-console-api -Dtest=DefaultConsoleTenantConfigInitApplicationServiceTest,ConsoleIdempotencyInterceptorTest test` | 通过 |

## 文档维护规则

- Controller 路径变化必须同步 `docs/api/console-api-protocol.md` 与 `docs/api/console-api.openapi.yaml`。
- “前端生成类型已同步”不等于“后端 Controller 与 OpenAPI 已同步”。
- 对外承诺事务语义时，文档必须区分：
  - 单请求在同一事务边界内执行；
  - 单项失败隔离、整体继续；
  - all-or-nothing 原子提交。
