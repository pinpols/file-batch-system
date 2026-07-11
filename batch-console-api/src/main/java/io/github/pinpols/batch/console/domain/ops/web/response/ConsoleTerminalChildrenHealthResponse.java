package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;

import java.util.Map;

/** 集群诊断 - 终态实例仍带活跃子节点检查。 */
public record ConsoleTerminalChildrenHealthResponse(
    Long terminalInstancesWithActiveChildren, Boolean healthy) {

  public static ConsoleTerminalChildrenHealthResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleTerminalChildrenHealthResponse(
        longValue(row, "terminalInstancesWithActiveChildren"), booleanValue(row, "healthy"));
  }
}
