package io.github.pinpols.batch.console.domain.ops.application.contract.response;

/** 集群诊断 - 终态实例仍带活跃子节点检查。 */
public record ConsoleTerminalChildrenHealthResponse(
    Long terminalInstancesWithActiveChildren, Boolean healthy) {}
