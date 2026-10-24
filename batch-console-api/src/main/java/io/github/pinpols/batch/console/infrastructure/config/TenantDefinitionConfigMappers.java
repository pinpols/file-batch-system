package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 租户初始化中作业和工作流定义使用的 Mapper 集合。 */
@Component
@RequiredArgsConstructor
final class TenantDefinitionConfigMappers {

  final JobDefinitionMapper jobDefinition;
  final WorkflowDefinitionMapper workflowDefinition;
  final WorkflowNodeMapper workflowNode;
  final WorkflowEdgeMapper workflowEdge;
}
