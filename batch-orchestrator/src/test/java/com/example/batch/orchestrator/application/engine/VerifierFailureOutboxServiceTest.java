package com.example.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VerifierFailureOutboxServiceTest {

  @Test
  void writesOneOutboxRowPerFailure() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
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
    ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
    List<DomainEvent> events = captor.getAllValues();
    assertThat(events).extracting(DomainEvent::eventType).containsOnly("verifier.failure.v1");
    assertThat(events).extracting(DomainEvent::aggregateId).containsOnly(7L);
    assertThat(events)
        .extracting(DomainEvent::eventKey)
        .containsExactlyInAnyOrder(
            "t1:verifier:42:EXPORT_FILE_EMPTY:0", "t1:verifier:42:EXPORT_HEADER_INVALID:1");
    assertThat(events.get(0).payload().toString()).contains("\"schemaVersion\":\"v1\"");
  }

  @Test
  void eventKeyIncludesIndexSoSameReasonDoesNotCollide() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
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

    ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(DomainEvent::eventKey)
        .containsExactly("t1:verifier:7:DUP_CODE:0", "t1:verifier:7:DUP_CODE:1");
  }

  @Test
  void noopWhenFailuresNull() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(1L).success(true).build();

    int written = service.writeVerifierFailures(command, new JobTaskEntity());

    assertThat(written).isZero();
    verify(publisher, never()).publish(any());
  }

  @Test
  void noopWhenFailuresEmpty() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(1L)
            .success(true)
            .verifierFailures(List.of())
            .build();

    int written = service.writeVerifierFailures(command, new JobTaskEntity());

    assertThat(written).isZero();
    verify(publisher, never()).publish(any());
  }

  @Test
  void skipsNullFailureEntries() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
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
    verify(publisher, org.mockito.Mockito.times(1)).publish(any());
  }
}
