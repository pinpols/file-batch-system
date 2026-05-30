package com.example.batch.console.domain.workflow.application;

import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import java.util.List;

/** 工作流定义应用服务：管理工作流定义的 CRUD 及 DAG 校验操作。 */
public interface ConsoleWorkflowDefinitionApplicationService {

  WorkflowDefinitionDetailResponse getById(Long id, String tenantId);

  WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request);

  WorkflowDefinitionDetailResponse update(Long id, WorkflowDefinitionSaveRequest request);

  void toggleEnabled(Long id, String tenantId, Boolean enabled);

  DagValidationResult validate(Long id, String tenantId);

  /**
   * DAG 静态校验结果。
   *
   * @param valid 是否通过（findings 中无 ERROR）
   * @param errors 兼容旧前端的字符串列表（保留至下个 minor 版本，建议消费 findings）
   * @param findings 结构化条目，包含 code / level / message / nodeCode / edgeId，前端可定位画布单元
   */
  record DagValidationResult(boolean valid, List<String> errors, List<Finding> findings) {

    /** 单条校验发现。`nodeCode` 与 `edgeId` 至多一个非空，None 表示规则不针对具体单元。 */
    public record Finding(
        String code, String level, String message, String nodeCode, String edgeId) {

      /** ERROR 级别常量。 */
      public static final String LEVEL_ERROR = "ERROR";

      /** WARNING 级别常量。 */
      public static final String LEVEL_WARNING = "WARNING";

      public static Finding error(String code, String message, String nodeCode, String edgeId) {
        return new Finding(code, LEVEL_ERROR, message, nodeCode, edgeId);
      }

      public static Finding warning(String code, String message, String nodeCode, String edgeId) {
        return new Finding(code, LEVEL_WARNING, message, nodeCode, edgeId);
      }
    }
  }
}
