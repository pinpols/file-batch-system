package io.github.pinpols.batch.console.domain.workflow.application;

import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
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
   * 列出工作流定义所有历史版本(workflow-dag-designer Polish — V167 历史表闭环)。
   *
   * <p>真实读 {@code batch.workflow_definition_version}(V167),version desc 排序,最新 version 携带 {@code
   * current=true}。历史表无数据时(刚迁移后)降级为单条 current,兼容 PR #370 降级返回行为。前端用此 list 渲染版本下拉,from/to
   * 选择仅一项时回退「to=current / from=空」。
   */
  List<WorkflowDefinitionVersionSummaryResponse> listVersions(Long id, String tenantId);

  /**
   * 获取指定版本的完整 definition(workflow-dag-designer Polish — V167 历史表闭环)。
   *
   * <p>当前 version(== 主表 version)直接返主表 + 关联节点边;历史 version 从 {@code workflow_definition_version} 反序列化
   * nodes_json / edges_json;不存在的版本一律 NOT_FOUND。
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
