package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkflowRunManagementApplicationServiceTest {

  @Test
  void supportsBothSkipNodeEntrypoints() {
    WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
    WorkflowNodeRunMapper workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(1L);
    run.setTenantId("tenant-A");
    run.setRunStatus("RUNNING");
    WorkflowNodeRunEntity node = new WorkflowNodeRunEntity();
    node.setId(2L);
    node.setNodeStatus("FAILED");
    when(workflowRunMapper.selectById("tenant-A", 1L)).thenReturn(run);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(1L, "NODE"))
        .thenReturn(node);

    WorkflowRunManagementApplicationService service = new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        mock(WorkflowTerminalOutboxService.class),
        mock(WorkflowDagService.class),
        mock(ObjectProvider.class),
        mock(OrchestratorJobMappers.class),
        null,
        null);

    assertThat(service.skipNode("tenant-A", 1L, "NODE").nodeStatus()).isEqualTo("SKIPPED");
    assertThat(service.skipNode("tenant-A", 1L, "NODE", "operator-1", "manual review"))
        .extracting(WorkflowManagementResults.NodeAction::nodeStatus)
        .isEqualTo("SKIPPED");
  }
}
