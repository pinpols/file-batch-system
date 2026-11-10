package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分发重试后的最终结果")
class DispatchStageOutcomeTest {
  @Test
  @DisplayName("发送失败后的重试未完成确认和收尾时仍保留发送失败")
  void failure_shouldPreserveSendFailure_whenRetryHasNotCompleted() {
    DispatchStageResult failed =
        DispatchStageResult.failure(DispatchStage.DISPATCH, "DISPATCH_SEND_FAILED", "发送失败");
    DispatchStageResult retry = DispatchStageResult.success(DispatchStage.RETRY);

    DispatchStageResult result = DispatchStageOutcome.failure(List.of(failed, retry), true);

    assertThat(result).isEqualTo(failed);
  }

  @Test
  @DisplayName("重试成功不能掩盖之前不属于发送阶段的失败")
  void failure_shouldPreserveUnrelatedEarlierFailure() {
    DispatchStageResult failed =
        DispatchStageResult.failure(DispatchStage.PREPARE, "PREPARE_FAILED", "准备失败");
    List<DispatchStageResult> results = List.of(
        failed,
        DispatchStageResult.success(DispatchStage.RETRY),
        DispatchStageResult.success(DispatchStage.ACK),
        DispatchStageResult.success(DispatchStage.COMPLETE));

    assertThat(DispatchStageOutcome.failure(results, true)).isEqualTo(failed);
  }

  @Test
  @DisplayName("外部结果不明应优先于普通发送失败返回")
  void failure_shouldPrioritizeUnknownExternalOutcome() {
    DispatchStageResult failed =
        DispatchStageResult.failure(DispatchStage.DISPATCH, "DISPATCH_SEND_FAILED", "发送失败");
    DispatchStageResult unknown = DispatchStageResult.failure(
        DispatchStage.RETRY, TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN, "回执未知");

    assertThat(DispatchStageOutcome.failure(List.of(failed, unknown), false)).isEqualTo(unknown);
  }
}
