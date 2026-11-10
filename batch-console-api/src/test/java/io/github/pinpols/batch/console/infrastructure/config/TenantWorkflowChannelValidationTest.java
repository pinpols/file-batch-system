package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionDagInspector;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("租户配置导入的文件等待渠道校验")
class TenantWorkflowChannelValidationTest {
  @Test
  @DisplayName("JSON 配置导入拒绝非字符串渠道，不写入节点")
  void shouldRejectInvalidChannelInTenantInit() {
    WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
    WorkflowNodeMapper nodeMapper = mock(WorkflowNodeMapper.class);
    JobDefinitionMapper jobMapper = mock(JobDefinitionMapper.class);
    WorkflowEdgeMapper edgeMapper = mock(WorkflowEdgeMapper.class);
    TenantDefinitionConfigMappers mappers =
        new TenantDefinitionConfigMappers(jobMapper, definitionMapper, nodeMapper, edgeMapper);
    WorkflowDefinitionWriteSupport writeSupport = new WorkflowDefinitionWriteSupport(
        nodeMapper, edgeMapper, mock(WorkflowDefinitionVersionMapper.class), new ObjectMapper());
    TenantWorkflowDefinitionApplySupport support = new TenantWorkflowDefinitionApplySupport(
        mappers, writeSupport, new WorkflowDefinitionDagInspector(jobMapper));
    WorkflowDefinitionEntity saved = new WorkflowDefinitionEntity();
    saved.setId(1L);
    saved.setVersion(1);
    when(definitionMapper.selectByUniqueKey("tenant-a", "WF", 1)).thenReturn(saved);
    WorkflowDefinitionSpec.NodeSpec node = new WorkflowDefinitionSpec.NodeSpec();
    node.setNodeCode("wait-file");
    node.setNodeType("WAIT");
    node.setNodeParams("""
        {"sensor_type":"FILE_ARRIVAL","sensor_spec":{"channelCode":42}}
        """);
    WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec();
    spec.setWorkflowCode("WF");
    spec.setWorkflowName("Workflow");
    spec.setWorkflowType("DAG");
    spec.setNodes(List.of(node));

    assertThatThrownBy(() -> support.upsertWorkflowDefinition("tenant-a", null, spec, "operator"))
        .isInstanceOf(BizException.class);
    verify(nodeMapper, never()).upsertWorkflowNode(any());
  }
}
