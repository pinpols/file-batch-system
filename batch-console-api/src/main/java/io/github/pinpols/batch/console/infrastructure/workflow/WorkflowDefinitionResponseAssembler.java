package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import java.time.Instant;
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
    Long definitionId = definition.getId();
    String tenantId = definition.getTenantId();
    String workflowCode = definition.getWorkflowCode();
    String workflowName = definition.getWorkflowName();
    String workflowType = definition.getWorkflowType();
    Long singleJobDefinitionId = definition.getSingleJobDefinitionId();
    String scheduleType = definition.getScheduleType();
    String scheduleExpression = definition.getScheduleExpr();
    String timezone = definition.getTimezone();
    String triggerMode = definition.getTriggerMode();
    String calendarCode = definition.getCalendarCode();
    String dependencyJobCode = definition.getDependsOnJobCode();
    Integer version = definition.getVersion();
    Boolean enabled = definition.getEnabled();
    String description = definition.getDescription();
    Instant createdAt = definition.getCreatedAt();
    Instant updatedAt = definition.getUpdatedAt();
    List<ConsoleWorkflowNodeResponse> nodeResponses =
        nodes.stream().map(this::toNodeResponse).toList();
    List<ConsoleWorkflowEdgeResponse> edgeResponses =
        edges.stream().map(this::toEdgeResponse).toList();
    return WorkflowDefinitionDetailResponse.builder()
        .id(definitionId)
        .tenantId(tenantId)
        .workflowCode(workflowCode)
        .workflowName(workflowName)
        .workflowType(workflowType)
        .singleJobDefinitionId(singleJobDefinitionId)
        .scheduleType(scheduleType)
        .scheduleExpr(scheduleExpression)
        .timezone(timezone)
        .triggerMode(triggerMode)
        .calendarCode(calendarCode)
        .dependsOnJobCode(dependencyJobCode)
        .version(version)
        .enabled(enabled)
        .description(description)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .nodes(nodeResponses)
        .edges(edgeResponses)
        .build();
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
    historicalDefinition.setSingleJobDefinitionId(currentDefinition.getSingleJobDefinitionId());
    historicalDefinition.setScheduleType(snapshot.getScheduleType());
    historicalDefinition.setScheduleExpr(snapshot.getScheduleExpr());
    historicalDefinition.setTimezone(snapshot.getTimezone());
    historicalDefinition.setTriggerMode(snapshot.getTriggerMode());
    historicalDefinition.setCalendarCode(snapshot.getCalendarCode());
    historicalDefinition.setDependsOnJobCode(snapshot.getDependsOnJobCode());
    historicalDefinition.setVersion(snapshot.getVersion());
    historicalDefinition.setEnabled(snapshot.getEnabled());
    historicalDefinition.setDescription(currentDefinition.getDescription());
    historicalDefinition.setCreatedAt(currentDefinition.getCreatedAt());
    historicalDefinition.setUpdatedAt(snapshot.getSavedAt());
    return toDetailResponse(historicalDefinition, nodes, edges);
  }

  private ConsoleWorkflowNodeResponse toNodeResponse(WorkflowNodeEntity node) {
    Long nodeId = node.getId();
    Long workflowDefinitionId = node.getWorkflowDefinitionId();
    String nodeCode = node.getNodeCode();
    String nodeName = node.getNodeName();
    String nodeType = node.getNodeType();
    String relatedJobCode = node.getRelatedJobCode();
    String workerGroup = node.getWorkerGroup();
    String windowCode = node.getWindowCode();
    Integer nodeOrder = node.getNodeOrder();
    String retryPolicy = node.getRetryPolicy();
    Integer retryMaxCount = node.getRetryMaxCount();
    Integer timeoutSeconds = node.getTimeoutSeconds();
    String maskedNodeParams = SecretMasking.maskSensitiveJson(node.getNodeParams());
    Boolean enabled = node.getEnabled();
    Instant createdAt = node.getCreatedAt();
    Instant updatedAt = node.getUpdatedAt();
    String crossDayDependencies = node.getCrossDayDependencies();
    Integer crossDayDependencyTimeoutSeconds = node.getCrossDayDependencyTimeoutSeconds();
    return ConsoleWorkflowNodeResponse.builder()
        .id(nodeId)
        .workflowDefinitionId(workflowDefinitionId)
        .nodeCode(nodeCode)
        .nodeName(nodeName)
        .nodeType(nodeType)
        .relatedJobCode(relatedJobCode)
        .workerGroup(workerGroup)
        .windowCode(windowCode)
        .nodeOrder(nodeOrder)
        .retryPolicy(retryPolicy)
        .retryMaxCount(retryMaxCount)
        .timeoutSeconds(timeoutSeconds)
        .nodeParams(maskedNodeParams)
        .enabled(enabled)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .crossDayDependencies(crossDayDependencies)
        .crossDayDependencyTimeoutSeconds(crossDayDependencyTimeoutSeconds)
        .build();
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
