package com.example.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.SensorType;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.service.task.TaskOutcomeService;
import com.example.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunFinishCommand;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SensorStateMachineTest {

  private final SensorPolicyRegistry registry = Mockito.mock(SensorPolicyRegistry.class);
  private final WorkflowNodeRunMapper nodeRunMapper = Mockito.mock(WorkflowNodeRunMapper.class);
  private final WorkflowNodeMapper nodeMapper = Mockito.mock(WorkflowNodeMapper.class);
  private final WorkflowRunMapper workflowRunMapper = Mockito.mock(WorkflowRunMapper.class);
  private final TaskOutcomeService taskOutcomeService = Mockito.mock(TaskOutcomeService.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SensorPolicy filePolicy = Mockito.mock(SensorPolicy.class);

  private SensorStateMachine sm;
  private static final Instant NOW = Instant.parse("2026-05-13T12:00:00Z");
  private static final Instant STARTED = Instant.parse("2026-05-13T11:59:00Z"); // 60s elapsed

  @BeforeEach
  void setUp() {
    sm =
        new SensorStateMachine(
            registry,
            nodeRunMapper,
            nodeMapper,
            workflowRunMapper,
            taskOutcomeService,
            objectMapper);
    when(filePolicy.type()).thenReturn(SensorType.FILE_ARRIVAL);
    when(registry.resolve(SensorType.FILE_ARRIVAL)).thenReturn(filePolicy);
  }

  @Test
  void matched_callsRecordNodeRunFinishSuccess() {
    seedHappyPath();
    when(filePolicy.probe(any())).thenReturn(SensorProbeResult.matched(Map.of("fileId", 42L)));

    sm.probeAndAdvance(nodeRun(0, 0), NOW);

    ArgumentCaptor<NodeRunFinishCommand> cap = ArgumentCaptor.forClass(NodeRunFinishCommand.class);
    verify(taskOutcomeService).recordNodeRunFinish(cap.capture());
    assertThat(cap.getValue().success()).isTrue();
    assertThat(cap.getValue().outputJson()).contains("\"fileId\":42");
  }

  @Test
  void notYet_updatesProbeStateOnly_noFinish() {
    seedHappyPath();
    when(filePolicy.probe(any())).thenReturn(SensorProbeResult.notYet());

    sm.probeAndAdvance(nodeRun(5, 0), NOW);

    verify(taskOutcomeService, never()).recordNodeRunFinish(any());
    Instant expectedNext = NOW.plusSeconds(30); // pollInterval=30
    verify(nodeRunMapper).updateSensorProbeState(eq(99L), eq(expectedNext), eq(NOW), eq(6), eq(0));
  }

  @Test
  void error_belowThreshold_increments_no_finish() {
    seedHappyPath();
    when(filePolicy.probe(any()))
        .thenReturn(SensorProbeResult.error("error.x", java.util.List.of()));

    sm.probeAndAdvance(nodeRun(2, 1), NOW); // already 1 error

    verify(taskOutcomeService, never()).recordNodeRunFinish(any());
    // exponential backoff: errors=2 → 2x poll
    verify(nodeRunMapper).updateSensorProbeState(eq(99L), any(), eq(NOW), eq(3), eq(2));
  }

  @Test
  void error_atThreshold_triggersFailure() {
    seedHappyPath();
    when(filePolicy.probe(any()))
        .thenReturn(
            SensorProbeResult.error(
                "error.workflow.sensor_probe_failed", java.util.List.of("FILE_ARRIVAL", "boom")));

    // already 2 consecutive errors; this would be 3rd → fail
    sm.probeAndAdvance(nodeRun(2, 2), NOW);

    ArgumentCaptor<NodeRunFinishCommand> cap = ArgumentCaptor.forClass(NodeRunFinishCommand.class);
    verify(taskOutcomeService).recordNodeRunFinish(cap.capture());
    assertThat(cap.getValue().success()).isFalse();
    assertThat(cap.getValue().errorKey()).isEqualTo("error.workflow.sensor_probe_failed");
  }

  @Test
  void elapsedExceedsTimeout_triggersTimeoutFailure_doesNotProbe() {
    seedHappyPath();
    WorkflowNodeRunEntity stale = nodeRun(10, 0);
    // started 1 hour ago, timeout=120 → timeout
    stale.setStartedAt(NOW.minusSeconds(3600));

    sm.probeAndAdvance(stale, NOW);

    verify(filePolicy, never()).probe(any());
    ArgumentCaptor<NodeRunFinishCommand> cap = ArgumentCaptor.forClass(NodeRunFinishCommand.class);
    verify(taskOutcomeService).recordNodeRunFinish(cap.capture());
    assertThat(cap.getValue().success()).isFalse();
    assertThat(cap.getValue().errorKey()).isEqualTo("error.workflow.sensor_timeout");
  }

  @Test
  void invalidNodeParams_failsFast() {
    WorkflowRunEntity wfRun = new WorkflowRunEntity();
    wfRun.setId(1L);
    wfRun.setTenantId("ta");
    wfRun.setWorkflowDefinitionId(7L);
    when(workflowRunMapper.selectByIdAnyTenant(1L)).thenReturn(wfRun);
    WorkflowNodeEntity wfNode = new WorkflowNodeEntity();
    wfNode.setNodeType("WAIT");
    wfNode.setNodeParams("{\"sensor_type\":\"FILE_ARRIVAL\"}"); // missing spec/timeout/etc
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(7L, "wait-1")).thenReturn(wfNode);

    sm.probeAndAdvance(nodeRun(0, 0), NOW);

    verify(filePolicy, never()).probe(any());
    ArgumentCaptor<NodeRunFinishCommand> cap = ArgumentCaptor.forClass(NodeRunFinishCommand.class);
    verify(taskOutcomeService).recordNodeRunFinish(cap.capture());
    assertThat(cap.getValue().errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
  }

  @Test
  void unknownSensorType_failsFast() {
    WorkflowRunEntity wfRun = new WorkflowRunEntity();
    wfRun.setId(1L);
    wfRun.setWorkflowDefinitionId(7L);
    when(workflowRunMapper.selectByIdAnyTenant(1L)).thenReturn(wfRun);
    WorkflowNodeEntity wfNode = new WorkflowNodeEntity();
    wfNode.setNodeType("WAIT");
    wfNode.setNodeParams(
        "{\"sensor_type\":\"BOGUS\",\"sensor_spec\":{},\"timeout_seconds\":120,"
            + "\"poll_interval_seconds\":30,\"on_timeout\":\"FAIL\"}");
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(7L, "wait-1")).thenReturn(wfNode);

    sm.probeAndAdvance(nodeRun(0, 0), NOW);

    verify(taskOutcomeService).recordNodeRunFinish(any());
  }

  // ── fixture helpers ────────────────────────────────────────────────────────

  private void seedHappyPath() {
    WorkflowRunEntity wfRun = new WorkflowRunEntity();
    wfRun.setId(1L);
    wfRun.setTenantId("ta");
    wfRun.setWorkflowDefinitionId(7L);
    when(workflowRunMapper.selectByIdAnyTenant(1L)).thenReturn(wfRun);
    WorkflowNodeEntity wfNode = new WorkflowNodeEntity();
    wfNode.setNodeType("WAIT");
    wfNode.setNodeParams(
        "{\"sensor_type\":\"FILE_ARRIVAL\","
            + "\"sensor_spec\":{\"pattern\":\"x-*\",\"maxAgeSeconds\":3600},"
            + "\"timeout_seconds\":120,"
            + "\"poll_interval_seconds\":30,"
            + "\"on_timeout\":\"FAIL\"}");
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(7L, "wait-1")).thenReturn(wfNode);
  }

  private WorkflowNodeRunEntity nodeRun(int probeCount, int errorCount) {
    WorkflowNodeRunEntity n = new WorkflowNodeRunEntity();
    n.setId(99L);
    n.setWorkflowRunId(1L);
    n.setNodeCode("wait-1");
    n.setNodeType("WAIT");
    n.setNodeStatus("RUNNING");
    n.setStartedAt(STARTED);
    n.setSensorProbeCount(probeCount);
    n.setSensorErrorCount(errorCount);
    return n;
  }
}
