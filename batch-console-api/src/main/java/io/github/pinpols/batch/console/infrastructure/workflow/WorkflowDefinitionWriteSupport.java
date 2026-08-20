package io.github.pinpols.batch.console.infrastructure.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 负责工作流节点、边和历史版本快照的持久化辅助操作。
 *
 * <p>节点/边是工作流定义聚合的一部分，版本快照则要求使用数据库写入后的实体序列化，避免 request DTO 与实际
 * 持久化字段出现差异。这里不负责事务，事务边界仍由应用服务的 create/update/fullUpdate 持有。
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
public final class WorkflowDefinitionWriteSupport {

  private static final TypeReference<List<WorkflowNodeEntity>> NODE_LIST_TYPE =
      new TypeReference<>() {};
  private static final TypeReference<List<WorkflowEdgeEntity>> EDGE_LIST_TYPE =
      new TypeReference<>() {};

  private final WorkflowNodeMapper nodeMapper;
  private final WorkflowEdgeMapper edgeMapper;
  private final WorkflowDefinitionVersionMapper versionMapper;
  private final ObjectMapper objectMapper;

  public void upsertNodesAndEdges(
      String tenantId, Long definitionId, WorkflowDefinitionSaveRequest request) {
    if (request.getNodes() != null) {
      for (WorkflowDefinitionSaveRequest.NodeItem node : request.getNodes()) {
        WorkflowNodeUpsertParam param = new WorkflowNodeUpsertParam();
        param.setTenantId(tenantId);
        param.setWorkflowDefinitionId(definitionId);
        param.setNodeCode(node.getNodeCode());
        param.setNodeName(node.getNodeName());
        param.setNodeType(node.getNodeType());
        param.setRelatedJobCode(node.getRelatedJobCode());
        param.setRelatedPipelineCode(node.getRelatedPipelineCode());
        param.setWorkerGroup(node.getWorkerGroup());
        param.setWindowCode(node.getWindowCode());
        param.setNodeOrder(node.getNodeOrder());
        param.setRetryPolicy(node.getRetryPolicy());
        param.setRetryMaxCount(node.getRetryMaxCount());
        param.setTimeoutSeconds(node.getTimeoutSeconds());
        param.setNodeParams(node.getNodeParams());
        param.setEnabled(node.getEnabled());
        nodeMapper.upsertWorkflowNode(param);
      }
    }
    if (request.getEdges() != null) {
      for (WorkflowDefinitionSaveRequest.EdgeItem edge : request.getEdges()) {
        WorkflowEdgeUpsertParam param = new WorkflowEdgeUpsertParam();
        param.setTenantId(tenantId);
        param.setWorkflowDefinitionId(definitionId);
        param.setFromNodeCode(edge.getFromNodeCode());
        param.setToNodeCode(edge.getToNodeCode());
        param.setEdgeType(edge.getEdgeType());
        param.setConditionExpr(edge.getConditionExpr());
        param.setEnabled(edge.getEnabled());
        edgeMapper.upsertWorkflowEdge(param);
      }
    }
  }

  public void appendVersionSnapshot(
      String tenantId,
      Long definitionId,
      String workflowCode,
      Integer version,
      WorkflowDefinitionSaveRequest body,
      String savedBy) {
    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, definitionId, null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(tenantId, definitionId, null));
    WorkflowDefinitionVersionInsertParam param = new WorkflowDefinitionVersionInsertParam();
    param.setTenantId(tenantId);
    param.setWorkflowDefinitionId(definitionId);
    param.setWorkflowCode(workflowCode);
    param.setVersion(version);
    param.setWorkflowName(body.getWorkflowName());
    param.setWorkflowType(body.getWorkflowType());
    param.setEnabled(body.getEnabled());
    try {
      param.setNodesJson(objectMapper.writeValueAsString(nodes));
      param.setEdgesJson(objectMapper.writeValueAsString(edges));
    } catch (JsonProcessingException exception) {
      // 主路径已持久化成功；序列化失败必须让事务回滚，避免主表和历史表分裂。
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.workflow.version_snapshot.serialize_failed",
          exception.getMessage());
    }
    param.setSavedBy(savedBy);
    // summary 暂留 null，前端尚未提交该字段。
    versionMapper.insertVersionSnapshot(param);
  }

  public List<WorkflowNodeEntity> readNodesJson(String json) {
    if (EmptyChecks.isBlank(json)) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, NODE_LIST_TYPE);
    } catch (JsonProcessingException exception) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.workflow.version_snapshot.deserialize_failed",
          exception.getMessage());
    }
  }

  public List<WorkflowEdgeEntity> readEdgesJson(String json) {
    if (EmptyChecks.isBlank(json)) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, EDGE_LIST_TYPE);
    } catch (JsonProcessingException exception) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.workflow.version_snapshot.deserialize_failed",
          exception.getMessage());
    }
  }
}
