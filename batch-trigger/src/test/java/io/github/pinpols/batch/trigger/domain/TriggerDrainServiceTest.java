package io.github.pinpols.batch.trigger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("排水用例操作顺序与准入状态")
class TriggerDrainServiceTest {
  private final TriggerDrainPort port = mock(TriggerDrainPort.class);
  private final TriggerDrainService service = new TriggerDrainService(port);

  @Test
  @DisplayName("启停操作先执行再读取状态，响应保留端口数据")
  void shouldApplyOperationBeforeReadingStatus() {
    Instant since = Instant.parse("2026-01-01T00:00:00Z");
    when(port.status()).thenReturn(new TriggerDrainPort.Status(true, since, "manual", "STANDBY"));

    var enabled = service.enable();
    var disabled = service.disable();

    assertThat(enabled.draining()).isTrue();
    assertThat(enabled.drainingSince()).isEqualTo(since);
    assertThat(enabled.reason()).isEqualTo("manual");
    assertThat(disabled.schedulerStatus()).isEqualTo("STANDBY");
    InOrder order = inOrder(port);
    order.verify(port).enable();
    order.verify(port).status();
    order.verify(port).disable();
    order.verify(port).status();
  }

  @Test
  @DisplayName("排水中仍拒绝新启动")
  void shouldRejectLaunchWhileDraining() {
    when(port.isDraining()).thenReturn(true);

    assertThatThrownBy(service::ensureAcceptingLaunches)
        .isInstanceOfSatisfying(
            BizException.class,
            error -> assertThat(error.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    when(port.isDraining()).thenReturn(false);
    service.ensureAcceptingLaunches();
  }
}
