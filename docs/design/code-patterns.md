# 代码模式规约

本文记录项目中已落地的消除重复分支、封装策略的具体模式，供新代码参照实现。

---

## 1. 路由表模式（Map<String, Handler>）

**问题：** 按字符串类型分派不同处理器时，if-chain 随业务增长无限膨胀，且新增类型需要修改主干方法。

**规定写法：** 构造期（或静态块）建立一次性路由表，`execute()` 方法只负责查表和调用。

**参照实现：** `DefaultCompensationService`

```java
private final Map<String, CompensationHandler> handlersByType = Map.of(
        "JOB",       this::rerunJob,
        "STEP",      this::rerunStep,
        "PARTITION", this::retryPartition,
        "FILE",      (cmd, cmdNo, traceId, entity) -> reprocessFile(cmd, traceId, entity),
        "BATCH",     this::rerunBatch,
        "DLQ",       this::replayDeadLetter
);

private Map<String, Object> execute(...) {
    CompensationHandler handler = handlersByType.get(compensationType);
    if (handler == null) throw new BizException(...);
    return handler.handle(command, commandNo, traceId, entity);
}
```

**适用条件：** ≥ 3 个分支、分支间逻辑独立、类型值为字符串或可 toString 的枚举。

---

## 2. 策略 + 模板方法模式（SpecHandler<T,E>）

**问题：** 同一"查找 → 跳过/更新/创建"三路循环在 N 个方法中逐字重复，每增加一种配置类型就复制一套。

**规定写法：** 定义 `SpecHandler<T,E>` 接口和公共 `applySpecs()` 模板，每种类型只提供一个 handler lambda，消除循环体重复。

**参照实现：** `DefaultConsoleTenantConfigInitApplicationService`

```java
// 公共模板（只写一次）
private <T, E> ItemStats applySpecs(List<T> specs, ApplyContext ctx, SpecHandler<T, E> handler) { ... }

// 7 个 upsertable 类型：insert/update 走同一 upsert 路径
private ItemStats applyFileChannels(List<FileChannelSpec> specs, ApplyContext ctx) {
    return applySpecs(specs, ctx, SpecHandler.upsertable(
            "channel", FileChannelSpec::getChannelCode,
            (tid, s) -> Optional.ofNullable(fileChannelConfigMapper.selectByUniqueKey(tid, s.getChannelCode())),
            (c, s) -> upsertFileChannel(c.tenantId(), s, c.operator())));
}

// 3 个特殊类型：insert/update 行为不同
private ItemStats applyJobDefinitions(List<JobDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(specs, ctx, SpecHandler.of(
            "jobDef", JobDefinitionSpec::getJobCode,
            (tid, s) -> Optional.ofNullable(jobDefinitionMapper.selectByUniqueKey(tid, s.getJobCode())),
            (c, s) -> insertJobDefinition(c.tenantId(), s, c.operator()),
            (c, s, existing) -> updateJobDefinition(existing, s, c.operator())));
}
```

**两个工厂：**
- `SpecHandler.upsertable(...)` — insert 和 update 走同一操作（7 种简单类型）
- `SpecHandler.of(...)` — insert 和 update 行为不同（作业定义、工作流定义、流水线定义）

---

## 3. 上下文对象替代多参数（ApplyContext / Command record）

**问题：** 同一批参数在 N 个方法间逐个传递，方法签名随需求增长超过 6 个参数上限。

**规定写法：** 将不变量封装为 `private record`（私有场景）或独立 Command/Param 类（公共接口）。

**参照实现：** `DefaultConsoleTenantConfigInitApplicationService.ApplyContext`

```java
private record ApplyContext(String tenantId, InitMode mode, String operator, boolean dryRun) {}

// 调用方只传 ctx，不再传 4 个独立参数
private ItemStats applyFileChannels(List<FileChannelSpec> specs, ApplyContext ctx) { ... }
```

---

## 4. 命名方法路由（inline else 超过 10 行时提取）

**问题：** `dispatchNode` 方法的 if-gateway / if-job / else(TASK) 结构中，else 分支体约 85 行，导致主干方法既是路由器又是实现，难以阅读。

**规定写法：** 每个分支体 > 10 行时提取为独立命名方法，主干方法变成纯路由器。

**参照实现：** `DefaultWorkflowNodeDispatchService.dispatchNode`

```java
// 重构前：两个 if + 一个 85 行 inline else
// 重构后：三路对称路由
if (isGatewayNode(workflowNode.getNodeType())) {
    return dispatchGatewayNode(jobInstance, workflowRun, node, sourcePayload);
}
if (isJobNode(workflowNode.getNodeType())) {
    return dispatchJobNode(jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
}
return dispatchTaskNode(jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
```

---

## 5. 共享前提逻辑消除（resolveLeftOperand 委托 resolveLiteralOperand）

**问题：** `resolveLeftOperand` 与 `resolveLiteralOperand` 前 6 个判断分支逐字重复，只有最后一行（`readPath` 还是 `UNRESOLVED`）不同。

**规定写法：** 让共享逻辑收敛到一处，差异以委托方式表达。

**参照实现：** `WorkflowConditionEvaluator`

```java
// 重构前：两个 ~20 行几乎相同的方法
// 重构后：resolveLeftOperand 委托给 resolveLiteralOperand，只处理差异路径
private Object resolveLeftOperand(String token, Map<String, Object> payload) {
    Object literal = resolveLiteralOperand(token, payload);
    if (literal != UNRESOLVED) {
        return literal;
    }
    return readPath(payload, stripOuterParentheses(token.trim()));
}
```

---

## 6. 错误结果工厂（failResult / errorResult）

**问题：** 多个 try-catch 块的 catch 部分构造完全相同的失败结果对象，每处复制 3 行。

**规定写法：** 提取 `private static XxxResult failResult(Command command, Exception ex)` 静态工厂。

**参照实现：** `RemoteFilesystemDispatchSupport`

```java
// 重构前：dispatchNas 和 dispatchOss 各自 catch 中重复 3 行
// 重构后：共享工厂
private static DispatchResult failResult(DispatchCommand command, Exception ex) {
    String externalRequestId = resolveExternalRequestId(command);
    String receiptCode = resolveReceiptCode(command, externalRequestId);
    return new DispatchResult(false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
}
```

---

## 7. CAS-miss 警告辅助方法（warnIfCasMiss）

**问题：** `if (updated <= 0) { log.warn("... CAS miss ...") }` 在同一类中出现 3 次，每次只有上下文字符串不同。

**规定写法：** 提取 `private void warnIfCasMiss(int updated, String context, long id)` 辅助方法。

**参照实现：** `DefaultTaskOutcomeService`

```java
private void warnIfCasMiss(int updated, String context, long partitionId) {
    if (updated <= 0) {
        log.warn("{} CAS miss - concurrent update likely already advanced: partitionId={}", context, partitionId);
    }
}

// 调用方
warnIfCasMiss(partitionUpdated, "partition markStatus(SUCCESS)", partition.getId());
warnIfCasMiss(retryUpdated,    "partition markRetrying",         partition.getId());
warnIfCasMiss(failUpdated,     "partition markStatus(FAILED)",   partition.getId());
```

---

## 识别需要重构的信号

| 信号 | 应用的模式 |
|---|---|
| `if (type == A) handleA(); else if (type == B) handleB()...` ≥ 3 分支 | Map 路由表（模式 1）|
| 同一"查找→跳过/更新/创建"循环出现 N 次，只有 mapper 调用不同 | SpecHandler + applySpecs（模式 2）|
| 一个方法传参 ≥ 5 个，且这些参数一起被传给下游 N 个方法 | ApplyContext/Command record（模式 3）|
| 主干方法有 if-A + if-B + inline-else，else 体 > 10 行 | 命名方法路由（模式 4）|
| 两个方法前 N 行相同，最后 1-2 行不同 | 委托共享逻辑（模式 5）|
| 多个 catch 块构造相同结构的失败对象 | failResult 工厂（模式 6）|
| `if (n <= 0) { log.warn(...) }` 出现 ≥ 3 次 | warnIfXxx 辅助方法（模式 7）|
