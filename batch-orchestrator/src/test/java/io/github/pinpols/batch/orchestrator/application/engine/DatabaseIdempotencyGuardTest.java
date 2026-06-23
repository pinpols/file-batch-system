package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.service.IdempotencyGuard;
import io.github.pinpols.batch.orchestrator.infrastructure.idempotency.DatabaseIdempotencyGuard;
import io.github.pinpols.batch.orchestrator.mapper.IdempotencyRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatabaseIdempotencyGuardTest {

  @Mock private IdempotencyRecordMapper mapper;

  private DatabaseIdempotencyGuard guard;

  @BeforeEach
  void setUp() {
    guard = new DatabaseIdempotencyGuard(mapper);
  }

  @Test
  void executeOnce_firstExecution_runsActionAndReturnsResult() {
    when(mapper.insertIfAbsent("t1", "key-1", null)).thenReturn(1);

    String result = guard.executeOnce("t1", "key-1", () -> "result-payload");

    assertThat(result).isEqualTo("result-payload");
    verify(mapper).insertIfAbsent("t1", "key-1", null);
    verify(mapper).updateResult("t1", "key-1", "result-payload");
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void executeOnce_firstExecutionWithNullResult_skipsUpdate() {
    when(mapper.insertIfAbsent("t1", "key-null", null)).thenReturn(1);

    String result = guard.executeOnce("t1", "key-null", () -> null);

    assertThat(result).isNull();
    verify(mapper).insertIfAbsent("t1", "key-null", null);
    verify(mapper, never()).updateResult(anyString(), anyString(), anyString());
  }

  @Test
  void executeOnce_duplicateKey_returnsStoredResultWithoutReexecuting() {
    when(mapper.insertIfAbsent("t1", "key-dup", null)).thenReturn(0);
    when(mapper.selectResultByKey("t1", "key-dup")).thenReturn("stored-result");

    IdempotencyGuard.IdempotentAction action = mock(IdempotencyGuard.IdempotentAction.class);
    String result = guard.executeOnce("t1", "key-dup", action);

    assertThat(result).isEqualTo("stored-result");
    verify(action, never()).execute();
  }

  @Test
  void isAlreadyExecuted_keyExists_returnsTrue() {
    // 行存在(可能是 result=null 占位行,也可能已回写完成),都算"已认领"
    when(mapper.countByKey("t1", "existing-key")).thenReturn(1);
    assertThat(guard.isAlreadyExecuted("t1", "existing-key")).isTrue();
  }

  @Test
  void isAlreadyExecuted_keyAbsent_returnsFalse() {
    when(mapper.countByKey("t1", "missing-key")).thenReturn(0);
    assertThat(guard.isAlreadyExecuted("t1", "missing-key")).isFalse();
  }
}
