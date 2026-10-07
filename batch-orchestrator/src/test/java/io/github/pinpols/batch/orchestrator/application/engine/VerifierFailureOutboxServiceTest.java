package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("校验失败出箱服务: 逐条落库, 事件键去重与空入参口径")
class VerifierFailureOutboxServiceTest {

  @Test
  @DisplayName("每条校验失败写一条事件, 事件类型, 聚合标识与载荷版本一致")
  void shouldWriteOneEventPerFailure_whenVerificationFails() {
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
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
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
    verify(publisher, times(2)).publish(captor.capture());
    List<DomainEvent> events = captor.getAllValues();
    assertThat(events).extracting(DomainEvent::eventType).containsOnly("verifier.failure.v1");
    assertThat(events).extracting(DomainEvent::aggregateId).containsOnly(7L);
    assertThat(events)
        .extracting(DomainEvent::eventKey)
        .containsExactlyInAnyOrder(
            "t1:verifier:42:EXPORT_FILE_EMPTY:0", "t1:verifier:42:EXPORT_HEADER_INVALID:1");
    assertThat(events.get(0).payload()).containsEntry("schemaVersion", "v1");
  }

  @Test
  @DisplayName("相同原因的多个失败按下标区分事件键, 不互相覆盖")
  void shouldIncludeIndexInEventKey_whenReasonsRepeat() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    Map<String, Object> a = Map.of("code", "DUP_CODE", "message", "first", "evidence", Map.of());
    Map<String, Object> b = Map.of("code", "DUP_CODE", "message", "second", "evidence", Map.of());
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId("t1")
        .taskId(7L)
        .success(true)
        .verifierFailures(List.of(a, b))
        .build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(1L);

    service.writeVerifierFailures(command, task);

    ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(publisher, times(2)).publish(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(DomainEvent::eventKey)
        .containsExactly("t1:verifier:7:DUP_CODE:0", "t1:verifier:7:DUP_CODE:1");
  }

  @Test
  @DisplayName("失败清单为空值时写入零条且不发布事件")
  void shouldWriteNothing_whenFailuresMissing() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(1L).success(true).build();

    int written = service.writeVerifierFailures(command, new JobTaskEntity());

    assertThat(written).isZero();
    verify(publisher, never()).publish(any());
  }

  @Test
  @DisplayName("失败清单为空列表时写入零条且不发布事件")
  void shouldWriteNothing_whenFailuresEmpty() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
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
  @DisplayName("清单中的空条目被跳过, 只写入有效条目")
  void shouldSkipNullEntries_whenWritingFailures() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    Map<String, Object> good = Map.of("code", "X", "message", "y", "evidence", Map.of());
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId("t1")
        .taskId(1L)
        .success(true)
        .verifierFailures(java.util.Arrays.asList(null, good, null))
        .build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(99L);

    int written = service.writeVerifierFailures(command, task);

    assertThat(written).isEqualTo(1);
    verify(publisher, times(1)).publish(any());
  }
}
