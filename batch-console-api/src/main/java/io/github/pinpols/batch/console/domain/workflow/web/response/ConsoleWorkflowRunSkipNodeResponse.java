package io.github.pinpols.batch.console.domain.workflow.web.response;

import java.util.Map;

/**
 * 工作流运行实例跳过节点结果（{@code POST /api/console/workflow-runs/{id}/skip-node}）。
 *
 * <p>编排内部 {@code /internal/workflow-runs/{id}/skip-node} 恒返回 {@code Map.of("id", "nodeCode",
 * "nodeStatus")} 三个固定键（{@code nodeStatus} 恒为 {@code "SKIPPED"}）。console 仅在 controller 边界经 {@link
 * #from(Map)} 透传转换，键一字不差。
 */
public record ConsoleWorkflowRunSkipNodeResponse(Long id, String nodeCode, String nodeStatus) {

  public static ConsoleWorkflowRunSkipNodeResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleWorkflowRunSkipNodeResponse(
        WorkflowResponseFieldReader.longValue(row, "id"),
        WorkflowResponseFieldReader.stringValue(row, "nodeCode"),
        WorkflowResponseFieldReader.stringValue(row, "nodeStatus"));
  }
}
