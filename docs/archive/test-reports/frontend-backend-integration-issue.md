# 前后端联调问题记录

日期：2026-03-29

## 现象

- 前端收到 `SYSTEM_ERROR`，但后端最初没有统一打印异常堆栈，定位较慢。
- `batch-trigger` 启动时报 Quartz 表不存在，触发链路无法稳定启动。
- `batch-console-api` 侧还能看到数据库参数绑定失败、下游 500、409 等异常。

## 日志证据

```text
ERROR ... ConsoleApiExceptionHandler - console unexpected exception
org.springframework.dao.DataIntegrityViolationException:
### Error querying database.  Cause: org.postgresql.util.PSQLException: 未设定参数值 5 的内容。
```

```text
ERROR ... ConsoleApiExceptionHandler - console unexpected exception
org.springframework.web.client.HttpServerErrorException$InternalServerError: 500 : "{"code":"SYSTEM_ERROR","message":"system error","data":null,"meta":null}"
```

```text
ERROR ... LocalDataSourceJobStore - ClusterManager: Error managing cluster: Failure obtaining db row lock: ERROR: relation "quartz.qrtz_locks" does not exist
ERROR ... SpringApplication - Application run failed
java.lang.IllegalStateException: failed to register quartz trigger: export_settlement_job
```

## 定位

1. `batch-console-api` 和 `batch-trigger` 的 REST 异常处理器之前只返回统一错误响应，没有统一 `log.error(...)`，所以前端拿到 500，但后端日志不完整。
2. 本地数据库虽然有 `batch` / `quartz` schema，但 Quartz 表没有完整初始化，导致 `batch-trigger` 不能正常启动。
3. 系统测试 seed 在重复执行时还不够幂等，曾出现主键/外键冲突。

## 影响

- 前端只能看到 `SYSTEM_ERROR`，无法快速定位后端根因。
- 触发、调度相关接口不可用或不稳定。
- 重复灌数、重启环境时容易把联调链路打断。

## 已处理

- 已给 `batch-console-api` 和 `batch-trigger` 的异常处理补上日志输出。
- 本地基础数据已重新灌入，数据库基础数据可用。

## 总结

这次联调问题主要是三件事叠加：

- 异常响应有了，但日志没跟上
- 本地环境 Quartz 表缺失
- seed 脚本幂等性不足

如果后续要继续压低联调成本，建议把 REST 全局异常日志、Quartz 初始化、seed 幂等性一起补齐。
## Flyway 与数据库现状核查

### 真实情况

- `batch_platform.flyway_schema_history` 目前只有 `V30`、`V31`、`V32` 三条记录，说明本地库是以 `V30` 基线接管后再继续增量迁移的。
- 目前 `batch` schema 下已经存在一批核心表，包括 `alert_event`、`job_definition`、`job_instance`、`job_task`、`workflow_definition`、`outbox_event`、`batch_day_instance`、`quartz.*` 等。
- `batch_business` 下目前只有示例业务表 `biz.customer_account`、`biz.settlement_batch`、`biz.settlement_detail`。

### 需要对齐的范围

后续前后端联调，建议把“系统所有用到的表”分成三类对齐：

1. `batch` 平台主表和运行表：配置、定义、运行、文件、告警、补偿、outbox、配额、批量日等。
2. `quartz` 调度元数据表：`qrtz_*` 全量表，保证 `batch-trigger` 能稳定注册和扫描触发器。
3. `biz` 业务示例表：`customer_account`、`settlement_batch`、`settlement_detail`，用于导入/导出联调。

### 为什么启动时没及时报错

- 启动自检是存在的，位于 `batch-orchestrator` 的 `StartupSelfCheck`。
- 它在 `ApplicationReadyEvent` 之后执行，只做日志输出，不会阻断启动。
- 当前自检只检查少量关键项：`batch`/`quartz` schema、`batch.batch_day_instance`、`batch.business_calendar`、以及 `V31` 相关列，不覆盖全部业务表，也不覆盖 `batch-trigger`、`batch-console-api` 的完整依赖。
- 因此，即使自检通过，也不代表系统所有联调用表都已经齐全。

### 优化建议

- 把联调前置校验扩展为“全量表存在性检查 + 关键列检查 + Quartz 初始化检查”。
- 对 `batch-trigger` 增加独立启动自检，不要只依赖 `orchestrator` 的自检。
- 把自检结果升级为可配置的启动门禁：本地可只 warn，联调/测试环境可 fail-fast。
- 统一 seed 和 migration 的幂等策略，避免重复执行时被主键/外键打断。

## 改成完整迁移一次

历史上曾用 `baseline-on-migrate`（例如基线到 `V30`）接管旧数据卷，容易导致「缺早期迁移表」的半残库。

当前约定：

- `application-local.yml` 保留 `baseline-on-migrate: true` 与 **`baseline-version: 1`（固定不变）**：只表示「V1 建 schema 已由 Docker init 预置」，**不要**把该数字改成当前最新迁移号（如 V34）
- Docker init 与 Flyway `V1` / `V30` 等 `IF NOT EXISTS` 兼容；Testcontainers 新库仍走完整迁移，与本地 baseline 语义独立
- 若旧数据卷状态混乱，仍建议 `docker compose down -v` 后重建

这样做的好处是：

- 避免“库里只有 `V30+`，但缺 `V1-V29` 表”的半残状态
- 让本地、测试、文档中的迁移链路保持一致
- 便于排查“到底是哪一个版本创建了哪一张表”

## 日志报错分析

### Console API 的报错

`batch-console-api` 的日志里主要有四类异常：

1. **数据库参数绑定失败**
   - 典型日志：`DataIntegrityViolationException` / `未设定参数值 5 的内容。`
   - 现象：控制台某些查询接口在组装 SQL 参数时缺参，最终被统一包装成 `SYSTEM_ERROR`。
   - 结论：这不是前端传参格式问题，而是后端 mapper 或调用链参数构造不完整，优先查 `FileArrivalGroupMapper.xml` 等相关查询。

2. **下游服务返回 500，但被上层重新包装成系统错误**
   - 典型日志：`HttpServerErrorException$InternalServerError: 500`，响应体为 `{"code":"SYSTEM_ERROR","message":"system error"...}`。
   - 现象：console 调用下游 REST 接口时，下游已经返回错误码，但 console 没有把下游响应语义透传给前端，而是统一转成了系统错误。
   - 结论：前端看到的是“统一 500”，但真实根因在 downstream，需要结合请求路径和 `requestId/traceId` 反查。

3. **下游业务冲突被转成 500**
   - 典型日志：`HttpClientErrorException$Conflict: 409`，message 是 `worker is decommissioned`。
   - 现象：这是可预期的业务冲突，但 console 当前仍以 `SYSTEM_ERROR` 形式返回。
   - 结论：建议把这类 409 保持为业务错误码，不要向前端降级成系统错误。

4. **不支持的方法被当成系统错误**
   - 典型日志：`HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`。
   - 现象：前端调用了错误的 HTTP method，后端在统一异常处理里返回了系统错误。
   - 结论：应区分参数错误 / 方法不匹配 / 系统异常，前端联调时也要核对接口方法。

### Trigger 服务的报错

`batch-trigger` 的日志主要是 Quartz 初始化失败：

- `quartz.qrtz_locks`、`quartz.qrtz_triggers`、`quartz.qrtz_job_details` 不存在
- 随后触发器注册失败：`failed to register quartz trigger: export_settlement_job`

这说明触发服务启动时强依赖 Quartz 元数据表，而当前本地环境的表结构并没有按它需要的完整状态初始化。

### 为什么启动自检没有提前拦住

- `StartupSelfCheck` 确实会在 `ApplicationReadyEvent` 之后执行。
- 它只做日志输出，不会阻断启动。
- 它只检查少量关键项，未覆盖 `trigger` 的 Quartz 全量依赖，也没覆盖所有联调用表。

### 结论

当前日志暴露的问题不是单一接口报错，而是三层叠加：

- `console-api` 的异常处理缺少足够细的错误分流，导致很多下游问题都变成 `SYSTEM_ERROR`
- `trigger` 对 Quartz 表的依赖在本地没有被完整满足
- 启动自检存在，但它不是强校验门禁，覆盖范围也不够全

## 修复进展

已修复的联调问题：

- `console-api` 的 REST 异常处理补齐了 `log.error` / `log.warn`，系统异常会打印堆栈。
- `batch-console-api` 的 `file arrival group` 查询从 `metadata_json ? 'fileGroupCode'` 改为 `jsonb_exists(...)`，避免 MyBatis 把 JSONB `?` 运算符误判成参数占位符。
- `batch-orchestrator` 的启动自检扩展为检查 `quartz.QRTZ_*` 关键表，不再只看 schema。
- Quartz 官方 JDBC JobStore 建表脚本已纳入 `batch-orchestrator/src/main/resources/db/migration/V2__create_quartz_tables_postgres_2_5_2.sql`，本地空库启动会走全量迁移。
- 本地 `batch-orchestrator` / `batch-trigger` 的 `application-local.yml` 保留 `baseline-on-migrate`，`baseline-version` 固定为 `1`（勿随新迁移递增）。

仍需继续保持的事项：

- 新增迁移必须继续按版本号递增，不要回写旧版本。
- 业务 seed 和测试 seed 仍要尽量保持幂等，避免重复执行中断联调。

