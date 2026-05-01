# ADR-005: 分区数量解析使用责任链模式（PartitionCountResolver）

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

批量作业的分区数量（`partitionCount`）来源有多个优先级层次：

1. 触发方在 `LaunchRequest.params` 中显式指定。
2. 作业定义（`JobDefinition`）中配置的默认值。
3. 平台全局默认值（`batch.defaults.partition-count`）。
4. 兜底硬编码值（1）。

最初在 `PartitionDispatchService` 中通过 if-else 链处理这四个来源，代码难以扩展（新增来源需修改核心类）且难以测试（需要构造复杂的上下文）。

## 决策

使用**责任链模式**（Chain of Responsibility）抽象分区数量解析：

```java
public interface PartitionCountResolver {
    OptionalInt resolve(LaunchRequest request, JobDefinition jobDefinition);
}
```

注册多个实现，按 Spring `@Order` 顺序形成链（首个返回正值的胜出，其余被覆盖时记 INFO log）：

| `@Order` | 实现类 | 解析来源 |
|---|---|---|
| 1 | `ExplicitPartitionCountResolver` | `params["partitionCount"]` 等显式 key |
| 2 | `SizeBasedPartitionCountResolver` | `估算条目数 ÷ targetItemsPerPartition`，或 `估算字节数 ÷ targetBytesPerPartition` |
| 3 | `RuntimeBasedPartitionCountResolver` | 历史执行时长 ÷ 目标分区时长 |
| 4 | `WorkerBasedPartitionCountResolver` | 在线 worker 数 × 并发因子（DYNAMIC=2，AUTO=1） |

兜底：所有 resolver 都返回 0 → `DefaultSchedulePlanBuilder.resolveDynamicPartitionCount` 返回 1。

链上第一个返回正值的解析器生效，后续 resolver 仍会被调用以便记录覆盖事件（INFO log，2026-05-01 hardening 加入）。这避免了"我配了 `targetItemsPerPartition` 但不生效"的静默调试痛点——日志格式：

```
INFO partition count resolver overridden: tenantId=t1, jobCode=JOB_X,
     winner=ExplicitPartitionCountResolver(3),
     shadowed=SizeBasedPartitionCountResolver(10)
```

组装方式（`@Order` 注解 + Spring `List` 注入自动排序）：

```java
@Component @Order(1) public class ExplicitPartitionCountResolver implements PartitionCountResolver { ... }
@Component @Order(2) public class SizeBasedPartitionCountResolver implements PartitionCountResolver { ... }
// ... 同样 Order(3) / Order(4)
```

**关键边界**：本责任链只决定 partition **数量**，不决定按什么字段切。具体切分维度（行 mod / 字节 range / 业务键 hash / 机构号）在 worker 端 step / plugin 自行解释 `PARTITION_NO` + `PARTITION_COUNT` + `PARTITION_KEY` + `input_snapshot` 决定。详见 [`core-model.md §3.6`](../core-model.md)。

## 后果

**正面**：
- 新增解析来源只需实现接口并注册 bean，不修改核心类（开闭原则）。
- 每个 Resolver 可以独立单元测试。
- 优先级顺序在 bean 列表中显式可见，无隐式逻辑。

**负面**：
- 四个来源用四个类稍显过度设计；如果解析来源稳定不变，简单 if-else 的可读性更高。
- 链的组装依赖 bean 列表顺序，顺序错误不会在编译期报错。

## 当前状态

责任链已实现并在 `DefaultSchedulePlanBuilder.resolveDynamicPartitionCount` 中使用。所有 resolver 全返 0 时显式 fallback 为 1，不会出现 NPE。

**2026-05-01 hardening**：

- 链上覆盖事件 INFO log（见上文格式），覆盖意图可观测
- 配套 `EffectiveTaskConfig` 加 3 字段 `partitionNo` / `partitionCount` / `partitionKey`，worker 端无需自行查 DB 即可读 partition 信息
- `batch-worker-import` 的 `ParseStep` 默认 partition-aware（按 `lineNo % count == partitionNo - 1` 流式过滤 NDJSON staging），开关 `template_config.partition_aware_parse=false`
- 守护测试：`DefaultSchedulePlanBuilderTest` 11 用例（含覆盖日志 + 单 resolver 不记日志）；`ParseStepPartitionSliceTest` 4 用例（直通 + 3 partition × 9 行零重叠 + 越界警告）
