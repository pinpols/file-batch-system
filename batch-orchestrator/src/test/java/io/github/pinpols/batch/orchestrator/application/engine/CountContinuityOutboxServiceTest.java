package io.github.pinpols.batch.orchestrator.application.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ADR-041 Phase1.3b:跨阶段 count 连续性核对(仅告警)。 */
@ExtendWith(MockitoExtension.class)
class CountContinuityOutboxServiceTest {

  @Mock private WorkflowRunMapper workflowRunMapper;
  @Mock private WorkflowEdgeMapper workflowEdgeMapper;
  @Mock private WorkflowNodeRunMapper workflowNodeRunMapper;
  @Mock private DomainEventPublisher domainEventPublisher;

  private CountContinuityOutboxService service;

  @BeforeEach
  void setUp() {
    service =
        new CountContinuityOutboxService(
            workflowRunMapper,
            workflowEdgeMapper,
            workflowNodeRunMapper,
            domainEventPublisher,
            new ObjectMapper());
  }

  private WorkflowRunEntity run() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(7L);
    run.setTenantId("t1");
    run.setWorkflowDefinitionId(3L);
    return run;
  }

  private WorkflowNodeRunEntity upstream(String code, String output) {
    WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
    entity.setNodeCode(code);
    entity.setOutput(output);
    return entity;
  }

  private WorkflowEdgeEntity edge(String fromCode) {
    WorkflowEdgeEntity edge = new WorkflowEdgeEntity();
    edge.setFromNodeCode(fromCode);
    return edge;
  }

  @Test
  @DisplayName("上游 outputCount 与本节点 inputCount 不符 → 发告警事件")
  void mismatch_publishesEvent() {
    when(workflowRunMapper.selectByIdAnyTenant(7L)).thenReturn(run());
    when(workflowEdgeMapper.selectIncomingEdges(3L, "PROCESS")).thenReturn(List.of(edge("IMPORT")));
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCodesIn(eq(7L), any()))
        .thenReturn(List.of(upstream("IMPORT", "{\"outputCount\":1000}")));

    service.checkContinuity(7L, "PROCESS", "{\"inputCount\":950}");

    verify(domainEventPublisher).publish(any(DomainEvent.class));
  }

  @Test
  @DisplayName("上游 outputCount 与本节点 inputCount 一致 → 不发事件")
  void match_noEvent() {
    when(workflowRunMapper.selectByIdAnyTenant(7L)).thenReturn(run());
    when(workflowEdgeMapper.selectIncomingEdges(3L, "PROCESS")).thenReturn(List.of(edge("IMPORT")));
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCodesIn(eq(7L), any()))
        .thenReturn(List.of(upstream("IMPORT", "{\"outputCount\":1000}")));

    service.checkContinuity(7L, "PROCESS", "{\"inputCount\":1000}");

    verify(domainEventPublisher, never()).publish(any());
  }

  @Test
  @DisplayName("本节点未上报 inputCount → 直接跳过,不查上游不发事件")
  void noInputCount_skips() {
    service.checkContinuity(7L, "PROCESS", "{\"outputCount\":5}");

    verifyNoInteractions(workflowRunMapper, workflowEdgeMapper, domainEventPublisher);
  }
}
