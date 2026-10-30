package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Workflow 初始化读取托管 Job 并写入定义、节点和边所需的 Mapper 集合。 */
@Component
@RequiredArgsConstructor
final class TenantDefinitionConfigMappers {

  final JobDefinitionMapper jobDefinition;
  final WorkflowDefinitionMapper workflowDefinition;
  final WorkflowNodeMapper workflowNode;
  final WorkflowEdgeMapper workflowEdge;
}
