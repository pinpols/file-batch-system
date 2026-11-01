package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.SystemException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;

@DisplayName("排水适配器隔离 Quartz 错误并保持操作顺序")
class TriggerDrainAdapterTest {
  private final TriggerGracefulShutdown shutdown = mock(TriggerGracefulShutdown.class);
  private final TriggerDrainAdapter adapter = new TriggerDrainAdapter(shutdown);

  @Test
  @DisplayName("排水变更保留原操作来源和状态")
  void shouldDelegateOperationsAndMapStatus() throws Exception {
    when(shutdown.status())
        .thenReturn(
            new TriggerGracefulShutdown.TriggerDrainStatus(true, null, "manual", "STANDBY"));

    adapter.enable();
    assertThat(adapter.status().schedulerStatus()).isEqualTo("STANDBY");
    adapter.disable();

    verify(shutdown).startDraining("manual-enable");
    verify(shutdown).stopDraining("manual-disable");
  }

  @Test
  @DisplayName("排水与状态查询的调度器错误保留原因并归为系统错误")
  void shouldTranslateSchedulerFailures() throws Exception {
    SchedulerException cause = new SchedulerException("unavailable");
    doThrow(cause).when(shutdown).startDraining("manual-enable");
    doThrow(cause).when(shutdown).stopDraining("manual-disable");
    when(shutdown.status()).thenThrow(cause);

    assertSystemFailure(adapter::enable, cause);
    assertSystemFailure(adapter::disable, cause);
    assertSystemFailure(adapter::status, cause);
  }

  private static void assertSystemFailure(Runnable action, SchedulerException cause) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(SystemException.class, error -> {
      assertThat(error.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR);
      assertThat(error).hasCause(cause);
    });
  }
}
