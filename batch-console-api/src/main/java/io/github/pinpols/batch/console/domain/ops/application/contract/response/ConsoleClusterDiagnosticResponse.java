package io.github.pinpols.batch.console.domain.ops.application.contract.response;

/**
 * 集群诊断聚合响应（{@code GET /cluster-diagnostic}）：shedLock / workers / outbox / terminalChildren 四块，
 * 与各自单端点同构。同时是 {@code ConsoleAiTools.getClusterDiagnostics} 的读取源，避免 service、controller
 * 和 AI 工具重复维护字段名字符串。
 */
public record ConsoleClusterDiagnosticResponse(
    ConsoleShedLockStatusResponse shedLock,
    ConsoleWorkerConsistencyResponse workers,
    ConsoleOutboxHealthResponse outbox,
    ConsoleTerminalChildrenHealthResponse terminalChildren) {}
