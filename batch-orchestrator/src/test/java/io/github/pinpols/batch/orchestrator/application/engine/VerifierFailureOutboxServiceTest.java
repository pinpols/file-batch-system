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
import io.github.pinpols.batch.orchestrator.domain.command.VerifierFailure;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.List;
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
    VerifierFailure failureA =
        new VerifierFailure("EXPORT_FILE_EMPTY", "no rows", java.util.Map.of("recordCount", 0));
    VerifierFailure failureB =
        new VerifierFailure("EXPORT_HEADER_INVALID", "missing header", java.util.Map.of());
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId("t1")
        .taskId(42L)
        .workerId("worker-A")
        .success(true)
        .build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(7L);

    int written = service.writeVerifierFailures(command, task, List.of(failureA, failureB));

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
    VerifierFailure a = new VerifierFailure("DUP_CODE", "first", java.util.Map.of());
    VerifierFailure b = new VerifierFailure("DUP_CODE", "second", java.util.Map.of());
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(7L).success(true).build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(1L);

    service.writeVerifierFailures(command, task, List.of(a, b));

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

    int written = service.writeVerifierFailures(command, new JobTaskEntity(), List.of());

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

    int written = service.writeVerifierFailures(command, new JobTaskEntity(), List.of());

    assertThat(written).isZero();
    verify(publisher, never()).publish(any());
  }

  @Test
  @DisplayName("清单中的空条目被跳过, 只写入有效条目")
  void shouldSkipNullEntries_whenWritingFailures() {
    DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    VerifierFailureOutboxService service = new VerifierFailureOutboxService(publisher);
    VerifierFailure good = new VerifierFailure("X", "y", java.util.Map.of());
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(1L).success(true).build();
    JobTaskEntity task = new JobTaskEntity();
    task.setJobInstanceId(99L);

    int written =
        service.writeVerifierFailures(command, task, java.util.Arrays.asList(null, good, null));

    assertThat(written).isEqualTo(1);
    verify(publisher, times(1)).publish(any());
  }
}
