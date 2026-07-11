package io.github.pinpols.batch.console.domain.workflow.web.response;

import java.util.Map;

/**
 * 工作流运行实例生命周期动作结果（cancel / terminate / pause / resume）。
 *
 * <p>编排内部 {@code /internal/workflow-runs/{id}/{action}} 恒返回 {@code Map.of("id", "status")} 两个固定键
 * （{@code id} 为 workflow_run 主键，{@code status} 为翻转后的目标状态）。console 仅在 controller 边界经 {@link
 * #from(Map)} 透传转换，编排 service 与 proxy 返回类型不动，键一字不差。
 */
public record ConsoleWorkflowRunActionResponse(Long id, String status) {

  public static ConsoleWorkflowRunActionResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleWorkflowRunActionResponse(
        WorkflowResponseFieldReader.longValue(row, "id"),
        WorkflowResponseFieldReader.stringValue(row, "status"));
  }
}
