package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.enums.BatchDayReplaySessionStatus;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplaySessionStatusParam;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 会话 Mapper 的默认委托：重开部分失败时统一使用 RUNNING 作为目标状态。 */
@DisplayName("批次日重放会话 Mapper 默认方法")
class BatchDayReplaySessionMapperDefaultMethodTest {

  @Test
  @DisplayName("重开部分失败会话时把 RUNNING 枚举编码传给带状态版本")
  void shouldDelegateWithRunningStatus_whenReopeningPartialFailed() {
    BatchDayReplaySessionMapper mapper =
        mock(BatchDayReplaySessionMapper.class, CALLS_REAL_METHODS);
    when(mapper.reopenPartialFailedForRetryWithStatus(any(), any(), any(), any()))
        .thenReturn(1);
    Instant now = Instant.parse("2026-11-12T00:00:00Z");

    int updated = mapper.reopenPartialFailedForRetry("t1", 7L, now);

    assertThat(updated).isEqualTo(1);
    ArgumentCaptor<String> targetStatus = ArgumentCaptor.forClass(String.class);
    verify(mapper)
        .reopenPartialFailedForRetryWithStatus(eq("t1"), eq(7L), eq(now), targetStatus.capture());
    assertThat(targetStatus.getValue()).isEqualTo(BatchDayReplaySessionStatus.RUNNING.code());
    assertThat(List.of(BatchDayReplaySessionStatus.RUNNING.code()))
        .contains(targetStatus.getValue());
  }

  @Test
  @DisplayName("默认委托不自行构造状态更新参数")
  void shouldNotTouchStatusUpdateParam_whenReopeningPartialFailed() {
    BatchDayReplaySessionMapper mapper =
        mock(BatchDayReplaySessionMapper.class, CALLS_REAL_METHODS);
    when(mapper.reopenPartialFailedForRetryWithStatus(any(), any(), any(), any()))
        .thenReturn(0);

    mapper.reopenPartialFailedForRetry("t1", 8L, Instant.parse("2026-11-12T00:00:00Z"));

    verify(mapper, org.mockito.Mockito.never())
        .updateStatus(any(UpdateBatchDayReplaySessionStatusParam.class));
  }
}
