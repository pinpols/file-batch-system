package com.example.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VerifierFailureOutboxServiceTest {

  @Test
  void writesOneOutboxRowPerFailure() {
    OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(mapper);
    Map<String, Object> failureA = new LinkedHashMap<>();
    failureA.put("code", "EXPORT_FILE_EMPTY");
    failureA.put("message", "no rows");
    failureA.put("evidence", Map.of("recordCount", 0));
    Map<String, Object> failureB = new LinkedHashMap<>();
    failureB.put("code", "EXPORT_HEADER_INVALID");
    failureB.put("message", "missing header");
    failureB.put("evidence", Map.of());
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(42L)
            .workerId("worker-A")
            .success(true)
            .verifierFailures(List.of(failureA, failureB))
            .build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(7L);

    int written = service.writeVerifierFailures(command, task);

    assertThat(written).isEqualTo(2);
    ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    List<OutboxEventEntity> events = captor.getAllValues();
    assertThat(events)
        .extracting(OutboxEventEntity::getEventType)
        .containsOnly("verifier.failure.v1");
    assertThat(events).extracting(OutboxEventEntity::getAggregateId).containsOnly(7L);
    assertThat(events)
        .extracting(OutboxEventEntity::getPublishStatus)
        .containsOnly(OutboxPublishStatus.NEW.code());
    assertThat(events)
        .extracting(OutboxEventEntity::getEventKey)
        .containsExactlyInAnyOrder(
            "t1:verifier:42:EXPORT_FILE_EMPTY:0", "t1:verifier:42:EXPORT_HEADER_INVALID:1");
    assertThat(events.get(0).getPayloadJson()).contains("\"schemaVersion\":\"v1\"");
  }

  @Test
  void eventKeyIncludesIndexSoSameReasonDoesNotCollide() {
    OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(mapper);
    Map<String, Object> a = Map.of("code", "DUP_CODE", "message", "first", "evidence", Map.of());
    Map<String, Object> b = Map.of("code", "DUP_CODE", "message", "second", "evidence", Map.of());
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(7L)
            .success(true)
            .verifierFailures(List.of(a, b))
            .build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(1L);

    service.writeVerifierFailures(command, task);

    ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(OutboxEventEntity::getEventKey)
        .containsExactly("t1:verifier:7:DUP_CODE:0", "t1:verifier:7:DUP_CODE:1");
  }

  @Test
  void noopWhenFailuresNull() {
    OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(mapper);
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(1L).success(true).build();

    int written = service.writeVerifierFailures(command, new JobTaskEntity());

    assertThat(written).isZero();
    verify(mapper, never()).insert(any());
  }

  @Test
  void noopWhenFailuresEmpty() {
    OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(mapper);
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(1L)
            .success(true)
            .verifierFailures(List.of())
            .build();

    int written = service.writeVerifierFailures(command, new JobTaskEntity());

    assertThat(written).isZero();
    verify(mapper, never()).insert(any());
  }

  @Test
  void skipsNullFailureEntries() {
    OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(mapper);
    Map<String, Object> good = Map.of("code", "X", "message", "y", "evidence", Map.of());
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(1L)
            .success(true)
            .verifierFailures(java.util.Arrays.asList(null, good, null))
            .build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(99L);

    int written = service.writeVerifierFailures(command, task);

    assertThat(written).isEqualTo(1);
    verify(mapper, org.mockito.Mockito.times(1)).insert(any());
  }
}
