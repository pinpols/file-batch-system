package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.asMap;

import java.util.Map;

/**
 * 集群诊断聚合响应（{@code GET /cluster-diagnostic}）：shedLock / workers / outbox / terminalChildren 四块，
 * 与各自单端点同构。同时是 {@code ConsoleAiTools.getClusterDiagnostics} 的读取源，避免 service、controller
 * 和 AI 工具重复维护字段名字符串。
 */
public record ConsoleClusterDiagnosticResponse(
    ConsoleShedLockStatusResponse shedLock,
    ConsoleWorkerConsistencyResponse workers,
    ConsoleOutboxHealthResponse outbox,
    ConsoleTerminalChildrenHealthResponse terminalChildren) {

  public static ConsoleClusterDiagnosticResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    Map<String, Object> shedLockRow = asMap(row.get("shedLock"));
    ConsoleShedLockStatusResponse shedLock = ConsoleShedLockStatusResponse.from(shedLockRow);
    Map<String, Object> workersRow = asMap(row.get("workers"));
    ConsoleWorkerConsistencyResponse workers = ConsoleWorkerConsistencyResponse.from(workersRow);
    Map<String, Object> outboxRow = asMap(row.get("outbox"));
    ConsoleOutboxHealthResponse outbox = ConsoleOutboxHealthResponse.from(outboxRow);
    Map<String, Object> terminalChildrenRow = asMap(row.get("terminalChildren"));
    ConsoleTerminalChildrenHealthResponse terminalChildren =
        ConsoleTerminalChildrenHealthResponse.from(terminalChildrenRow);
    return new ConsoleClusterDiagnosticResponse(shedLock, workers, outbox, terminalChildren);
  }
}
