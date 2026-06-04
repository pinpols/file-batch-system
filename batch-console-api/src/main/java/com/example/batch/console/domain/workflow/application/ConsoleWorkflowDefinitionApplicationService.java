package com.example.batch.console.domain.workflow.application;

import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
import java.util.List;

/** 工作流定义应用服务：管理工作流定义的 CRUD 及 DAG 校验操作。 */
public interface ConsoleWorkflowDefinitionApplicationService {

  WorkflowDefinitionDetailResponse getById(Long id, String tenantId);

  WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request);

  WorkflowDefinitionDetailResponse update(Long id, WorkflowDefinitionSaveRequest request);

  /**
   * BE Spike:画布全量替换 (definition + nodes + edges)。
   *
   * <p>同事务:删旧 node/edge → 重写 → version += 1;锁不归属当前 user / expectedVersion 不匹配 → BizException。
   */
  WorkflowDefinitionDetailResponse fullUpdate(
      Long id, WorkflowDefinitionFullUpdateRequest request, String currentUser);

  void toggleEnabled(Long id, String tenantId, Boolean enabled);

  DagValidationResult validate(Long id, String tenantId);

  /**
   * 列出工作流定义的历史版本(降级版本)。
   *
   * <p>当前实现:平台尚无 {@code workflow_definition_version} 历史表,该方法仅返回单条"当前版本"摘要,等同于 workflow_definition
   * 主表当前快照。完整历史 follow-up 需新 migration,届时改为读 archive 表。前端用此 list 渲染版本下拉,from/to 选择仅一项时回退「to=current
   * / from=空」。
   */
  List<WorkflowDefinitionVersionSummaryResponse> listVersions(Long id, String tenantId);

  /**
   * 获取指定版本的完整 definition(降级版本)。
   *
   * <p>无历史表场景下:仅支持 {@code version == 当前版本} 的请求;其他版本号一律 NOT_FOUND。
   */
  WorkflowDefinitionDetailResponse getVersion(Long id, String tenantId, Integer version);

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
