package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 负责把工作流持久化实体转换为 Console 固定响应 DTO。
 *
 * <p>响应字段属于前后端契约，集中组装可以避免 create、当前版本读取和历史版本读取各自遗漏字段。该类只做
 * 无副作用的转换，不参与租户判断、事务或缓存失效。
 */
@Component
public final class WorkflowDefinitionResponseAssembler {

  public WorkflowDefinitionDetailResponse toDetailResponse(
      WorkflowDefinitionEntity definition,
      List<WorkflowNodeEntity> nodes,
      List<WorkflowEdgeEntity> edges) {
    return new WorkflowDefinitionDetailResponse(
        definition.getId(),
        definition.getTenantId(),
        definition.getWorkflowCode(),
        definition.getWorkflowName(),
        definition.getWorkflowType(),
        definition.getVersion(),
        definition.getEnabled(),
        definition.getDescription(),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        nodes.stream().map(this::toNodeResponse).toList(),
        edges.stream().map(this::toEdgeResponse).toList());
  }

  public WorkflowDefinitionDetailResponse toHistoricalDetailResponse(
      WorkflowDefinitionEntity currentDefinition,
      WorkflowDefinitionVersionEntity snapshot,
      List<WorkflowNodeEntity> nodes,
      List<WorkflowEdgeEntity> edges) {
    WorkflowDefinitionEntity historicalDefinition = new WorkflowDefinitionEntity();
    historicalDefinition.setId(currentDefinition.getId());
    historicalDefinition.setTenantId(currentDefinition.getTenantId());
    historicalDefinition.setWorkflowCode(snapshot.getWorkflowCode());
    historicalDefinition.setWorkflowName(snapshot.getWorkflowName());
    historicalDefinition.setWorkflowType(snapshot.getWorkflowType());
    historicalDefinition.setVersion(snapshot.getVersion());
    historicalDefinition.setEnabled(snapshot.getEnabled());
    historicalDefinition.setDescription(currentDefinition.getDescription());
    historicalDefinition.setCreatedAt(currentDefinition.getCreatedAt());
    historicalDefinition.setUpdatedAt(snapshot.getSavedAt());
    return toDetailResponse(historicalDefinition, nodes, edges);
  }

  private ConsoleWorkflowNodeResponse toNodeResponse(WorkflowNodeEntity node) {
    return new ConsoleWorkflowNodeResponse(
        node.getId(),
        node.getWorkflowDefinitionId(),
        node.getNodeCode(),
        node.getNodeName(),
        node.getNodeType(),
        node.getRelatedJobCode(),
        node.getRelatedPipelineCode(),
        node.getWorkerGroup(),
        node.getWindowCode(),
        node.getNodeOrder(),
        node.getRetryPolicy(),
        node.getRetryMaxCount(),
        node.getTimeoutSeconds(),
        node.getNodeParams(),
        node.getEnabled(),
        node.getCreatedAt(),
        node.getUpdatedAt(),
        node.getCrossDayDependencies(),
        node.getCrossDayDependencyTimeoutSeconds());
  }

  private ConsoleWorkflowEdgeResponse toEdgeResponse(WorkflowEdgeEntity edge) {
    return new ConsoleWorkflowEdgeResponse(
        edge.getId(),
        edge.getWorkflowDefinitionId(),
        edge.getFromNodeCode(),
        edge.getToNodeCode(),
        edge.getEdgeType(),
        edge.getConditionExpr(),
        edge.getEnabled(),
        edge.getCreatedAt(),
        edge.getUpdatedAt());
  }
}
