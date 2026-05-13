# ADR-028 · Sensor WAIT 节点

- **Status**: Proposed（2026-05-13）
- **Date**: 2026-05-13
- **Related**: ADR-009（节点参数 DSL，sensor 输出可被下游 DSL 引用）/ ADR-025（静态校验，加 V16 sensor_spec 校验）
- **Source**: 行业对标改进计划 §3 P0-1（[`docs/analysis/industry-benchmark-improvement-plan.md`](../../analysis/industry-benchmark-improvement-plan.md)）

## 背景

当前 workflow 节点的等待语义**仅限于"等上游 Job 完成"**：

```
START → JOB_A → JOB_B → END
        SUCCESS  SUCCESS
```

实际业务场景大量需要"等外部信号"，本系统目前**做不到**：

| 场景 | 当前只能 | 需要 |
|---|---|---|
| 等上游系统把 T-1 结算文件 push 到 SFTP | cron 定时轮询 + 文件不存在直接失败 | 等到出现再继续 |
| 等外部 API 返回 status=READY 才开始本系统加工 | 同上 | HTTP 轮询直到匹配 |
| 等 Kafka 上游 topic 消费位点推进到 T 时刻 | 无方法 | offset 比对 |
| 等业务库某种"信号行"出现 | 无方法 | SQL 探测 |

行业对标：Airflow `ExternalTaskSensor` / `FileSensor` / `HttpSensor` / `SqlSensor` 都做了 N 多年。本系统未补。

## 决策

新增节点类型 **`WAIT`**（与 START/END/TASK/GATEWAY/FILE_STEP/JOB 同层），承载"等待外部条件"语义。

### 数据模型

`workflow_node.node_type = WAIT`，`node_params` JSONB 内嵌 sensor 配置：

```json
{
  "sensor_type": "FILE_ARRIVAL | HTTP_POLL | KAFKA_OFFSET | DB_ROW_EXISTS",
  "sensor_spec": {
    // sensor_type=FILE_ARRIVAL:
    //   { "channelCode": "sftp_bank_in", "pattern": "settle-*.csv", "maxAgeSeconds": 3600 }
    // sensor_type=HTTP_POLL:
    //   { "url": "https://...", "method": "GET", "headersJson": "...", "matchExpr": "$.status=='READY'" }
    // sensor_type=KAFKA_OFFSET:
    //   { "topic": "upstream.settle.v1", "partition": 0, "minOffset": 123456 }
    // sensor_type=DB_ROW_EXISTS:
    //   { "schema": "biz", "sql": "SELECT 1 FROM signal WHERE biz_date=:bizDate AND status='READY' LIMIT 1" }
  },
  "timeout_seconds": 3600,
  "poll_interval_seconds": 30,
  "on_timeout": "FAIL | SKIP_DOWNSTREAM"
}
```

### 执行机制

- 节点 `WAIT` 在 `WorkflowSchedulePlanner` 派发阶段，**不**走 worker partition 派发链；而是产生 `workflow_node_run.status = RUNNING` 直接交给新 `SensorPollScheduler`
- `SensorPollScheduler` 周期（默认每 10s）扫所有 `RUNNING` 的 WAIT 节点：
  1. 解析 `node_params.sensor_spec` + 选 `SensorPolicy` 实现
  2. 调 `policy.probe(sensorSpec, ctx)` 返回 `MATCHED | NOT_YET | TIMEOUT | ERROR`
  3. 状态机推进：
     - MATCHED → workflow_node_run.status=SUCCESS，下游派发
     - TIMEOUT + on_timeout=FAIL → workflow_node_run.status=FAILED
     - TIMEOUT + on_timeout=SKIP_DOWNSTREAM → workflow_node_run.status=SKIPPED，下游不派发但 workflow 整体继续（沿 SUCCESS 边走）
     - ERROR → workflow_node_run.status=FAILED + 写 error_key
- 探测结果（如 FILE_ARRIVAL 命中的 fileId）写到 `workflow_node_run.output` JSONB，下游节点可以通过 ADR-009 DSL 引用：
  ```
  $.nodes.<WAIT_NODE_CODE>.output.fileId
  ```

### SPI 设计

```java
public interface SensorPolicy {
  SensorType type();  // 类型枚举绑定
  SensorProbeResult probe(SensorContext ctx);  // 单次探测
}

public record SensorContext(
    String tenantId,
    Long workflowNodeRunId,
    Map<String, Object> sensorSpec,
    Map<String, Object> workflowRunVars,  // bizDate / traceId 等
    Duration timeRemaining               // = timeout_seconds - elapsed
) {}

public record SensorProbeResult(
    Status status,                       // MATCHED / NOT_YET / TIMEOUT / ERROR
    Map<String, Object> output,          // MATCHED 时写到 workflow_node_run.output
    String errorMessageKey,              // ERROR 时 i18n key
    List<String> errorArgs
) {
  public enum Status { MATCHED, NOT_YET, TIMEOUT, ERROR }
}
```

4 个内置实现：

| 实现 | output key |
|---|---|
| `FileArrivalSensorPolicy` | `fileId / fileName / arrivalTime` |
| `HttpPollSensorPolicy` | `responseStatus / responseBody`（匹配通过的最后一次响应） |
| `KafkaOffsetSensorPolicy` | `currentOffset / topicPartition` |
| `DbRowExistsSensorPolicy` | `rowFound / firstRowJson` |

### Fail-safe 设计

- **超时保底**：scheduler 每次 probe 前先比对 `elapsed >= timeout_seconds` 直接返回 TIMEOUT，policy 不依赖外部时钟
- **并发幂等**：同一 WAIT 节点的 probe 拿 PG row-level lock（`SELECT ... FOR UPDATE SKIP LOCKED`），同一节点不会被多 scheduler 实例同时探测
- **外部错误不阻塞 workflow**：单次 ERROR 不立刻 FAILED，重试 3 次（指数退避）后才标失败；防对端临时抖动
- **资源沙箱**：HTTP_POLL 走独立连接池（默认 100 conns / 10s 超时）；DB_ROW_EXISTS 走 routing datasource business 库 + readonly 事务

## 校验扩展（ADR-025 V16）

`WorkflowGraphValidator` 加规则 V16：

| 子规则 | 错误码 | 说明 |
|---|---|---|
| V16-a | `WAIT_SENSOR_TYPE_MISSING` | WAIT 节点 node_params 缺 sensor_type |
| V16-b | `WAIT_SENSOR_TYPE_INVALID` | sensor_type 不在 4 个 enum 内 |
| V16-c | `WAIT_SENSOR_SPEC_INVALID` | sensor_spec 按 type 结构校验失败（FILE_ARRIVAL 缺 channelCode 等） |
| V16-d | `WAIT_TIMEOUT_LESS_THAN_POLL` | timeout_seconds <= poll_interval_seconds |
| V16-e | `WAIT_DB_SQL_NOT_SELECT` | DB_ROW_EXISTS 的 sql 必须是 readonly SELECT（JSqlParser AST 校验） |
| V16-f | `WAIT_DB_SCHEMA_NOT_ALLOWED` | DB_ROW_EXISTS 的 schema 必须在 `batch.security.allowed-schemas` 白名单内 |

## 不做的

- **不做 cron-style sensor**（"每天 8 点开始 wait"）—— workflow_definition.schedule_type 已经覆盖
- **不做 sensor 节点链式串联**（一个 sensor 出来直接驱动另一个 sensor）—— 用 GATEWAY + 2 个 WAIT 串行表达，避免特殊语法
- **不做自定义 sensor 扩展点**（如让用户写 Groovy）—— 4 个内置覆盖 95% 场景，自定义引入太多沙箱风险

## 实施分阶段

| Stage | 内容 | 估算 |
|---|---|---|
| **S1** | enum + ADR + validator 校验规则（OpenAPI 同步） | 0.5d |
| **S2** | SensorPolicy SPI + 4 个内置实现 + 单测 | 1.5d |
| **S3** | SensorPollScheduler + 状态机推进 + ShedLock 防多实例并发 | 1d |
| **S4** | 前端 WorkflowDesigner WAIT 节点 + 4 sensor_type 表单分支 | 0.5d |
| **S5** | 测试 fixture（ta/tb/tc 之一加 WAIT 节点示范）+ E2E | 0.5d |

总计 **4 工人日**。

## 风险

- **轮询风暴**：scheduler 每 10s 扫所有 RUNNING WAIT，1000+ 等待中可能 DB 压力
  - 缓解：scheduler 只查 `next_probe_at <= now()`（policy 返回下次探测时间）
- **HTTP 端点失活**：HTTP_POLL sensor 调死了对端
  - 缓解：超时严格 10s + 连接池上限 + 失败后退避到 2x poll_interval
- **DB_ROW_EXISTS schema 注入**：白名单 + JSqlParser AST 双重防护

## 关联

- ADR-009：sensor.output 走同一 DSL 暴露给下游节点
- ADR-025：V16 加入静态校验集合
- ADR-014/016：sensor 节点不走 worker partition 链，不需要 CLAIM/lease，状态机入口独立
