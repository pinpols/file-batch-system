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

注册多个实现，按优先级顺序形成链：

| 顺序 | 实现类 | 解析来源 |
|---|---|---|
| 1 | `RequestParamResolver` | `request.params["partitionCount"]` |
| 2 | `JobDefinitionResolver` | `jobDefinition.defaultPartitionCount` |
| 3 | `GlobalDefaultResolver` | `application.yml` 全局配置 |
| 4 | `FallbackResolver` | 硬编码 1 |

链上第一个返回非空的解析器生效，后续不再调用。

组装方式：

```java
@Bean
public List<PartitionCountResolver> partitionCountResolvers(
        GlobalDefaultResolver globalDefaultResolver) {
    return List.of(
        new RequestParamResolver(),
        new JobDefinitionResolver(),
        globalDefaultResolver,
        new FallbackResolver()
    );
}
```

## 后果

**正面**：
- 新增解析来源只需实现接口并注册 bean，不修改核心类（开闭原则）。
- 每个 Resolver 可以独立单元测试。
- 优先级顺序在 bean 列表中显式可见，无隐式逻辑。

**负面**：
- 四个来源用四个类稍显过度设计；如果解析来源稳定不变，简单 if-else 的可读性更高。
- 链的组装依赖 bean 列表顺序，顺序错误不会在编译期报错。

## 当前状态

责任链已实现并在 `PartitionDispatchService` 中使用。`FallbackResolver` 保证链永远有返回值，不会出现 NPE。
