package io.github.pinpols.batch.console.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowDefinitionWriteSupportTest {

  @Test
  void shouldPropagateCrossDayDependencyFieldsToNodeWrite() {
    WorkflowNodeMapper nodeMapper = mock(WorkflowNodeMapper.class);
    WorkflowDefinitionWriteSupport writeSupport = new WorkflowDefinitionWriteSupport(
        nodeMapper,
        mock(WorkflowEdgeMapper.class),
        mock(WorkflowDefinitionVersionMapper.class),
        new ObjectMapper());
    WorkflowDefinitionSaveRequest.NodeItem node = new WorkflowDefinitionSaveRequest.NodeItem();
    node.setNodeCode("load_source");
    node.setNodeType("JOB");
    node.setCrossDayDependencies("[{\"alias\":\"previous_day\",\"jobCode\":\"LOAD_SOURCE\"}]");
    node.setCrossDayDependencyTimeoutSeconds(7200);
    WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest();
    request.setNodes(List.of(node));

    writeSupport.upsertNodesAndEdges("tenant_a", 42L, request);

    ArgumentCaptor<WorkflowNodeUpsertParam> captor =
        ArgumentCaptor.forClass(WorkflowNodeUpsertParam.class);
    verify(nodeMapper).upsertWorkflowNode(captor.capture());
    assertThat(captor.getValue().getCrossDayDependencies())
        .isEqualTo("[{\"alias\":\"previous_day\",\"jobCode\":\"LOAD_SOURCE\"}]");
    assertThat(captor.getValue().getCrossDayDependencyTimeoutSeconds()).isEqualTo(7200);
  }
}
