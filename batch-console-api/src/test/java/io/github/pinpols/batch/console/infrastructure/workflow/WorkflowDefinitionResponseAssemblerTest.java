package io.github.pinpols.batch.console.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionResponseAssemblerTest {

  private final WorkflowDefinitionResponseAssembler assembler =
      new WorkflowDefinitionResponseAssembler();

  @Test
  void assemblesStableDefinitionNodeAndEdgeContract() {
    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(7L);
    definition.setTenantId("tenant-a");
    definition.setWorkflowCode("wf-a");
    definition.setWorkflowName("Settlement");
    definition.setWorkflowType("DAG");
    definition.setVersion(3);
    definition.setEnabled(true);

    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setId(8L);
    node.setWorkflowDefinitionId(7L);
    node.setNodeCode("IMPORT");
    node.setNodeType("JOB");
    node.setRelatedJobCode("job-import");
    node.setEnabled(true);

    WorkflowEdgeEntity edge = new WorkflowEdgeEntity();
    edge.setId(9L);
    edge.setWorkflowDefinitionId(7L);
    edge.setFromNodeCode("START");
    edge.setToNodeCode("IMPORT");
    edge.setEdgeType("SUCCESS");
    edge.setEnabled(true);

    var response = assembler.toDetailResponse(definition, List.of(node), List.of(edge));

    assertThat(response.id()).isEqualTo(7L);
    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.nodes()).singleElement().satisfies(item -> {
      assertThat(item.id()).isEqualTo(8L);
      assertThat(item.nodeCode()).isEqualTo("IMPORT");
      assertThat(item.relatedJobCode()).isEqualTo("job-import");
    });
    assertThat(response.edges()).singleElement().satisfies(item -> {
      assertThat(item.id()).isEqualTo(9L);
      assertThat(item.fromNodeCode()).isEqualTo("START");
      assertThat(item.toNodeCode()).isEqualTo("IMPORT");
    });
  }
}
