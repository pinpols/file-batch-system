package io.github.pinpols.batch.orchestrator.domain.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批次日重放状态更新参数:必填字段约束")
class UpdateBatchDayReplayEntryStatusParamTest {

  private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

  @Test
  @DisplayName("具名工厂固定各状态需要写入的字段")
  void shouldBuildLegalStateShapesFromNamedFactories() {
    UpdateBatchDayReplayEntryStatusParam running =
        UpdateBatchDayReplayEntryStatusParam.running(1L, 2L, NOW, NOW);
    UpdateBatchDayReplayEntryStatusParam succeeded =
        UpdateBatchDayReplayEntryStatusParam.succeededWithResult(3L, 4L, NOW);
    UpdateBatchDayReplayEntryStatusParam failed =
        UpdateBatchDayReplayEntryStatusParam.failedWithResult(5L, 6L, "failed", NOW);

    assertThat(running.getToStatus()).isEqualTo("RUNNING");
    assertThat(running.getRerunInstanceId()).isEqualTo(2L);
    assertThat(running.getFinishedAt()).isNull();
    assertThat(succeeded.getId()).isEqualTo(3L);
    assertThat(succeeded.getNow()).isEqualTo(NOW);
    assertThat(succeeded.getToStatus()).isEqualTo("SUCCEEDED");
    assertThat(succeeded.getResultVersionId()).isEqualTo(4L);
    assertThat(succeeded.getStartedAt()).isEqualTo(NOW);
    assertThat(succeeded.getFinishedAt()).isEqualTo(NOW);
    assertThat(failed.getToStatus()).isEqualTo("FAILED");
    assertThat(failed.getFailureReason()).isEqualTo("failed");
  }

  @Test
  @DisplayName("任一必填字段缺失时拒绝构造")
  void shouldRejectMissingRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> UpdateBatchDayReplayEntryStatusParam.succeededWithResult(null, 2L, NOW))
        .withMessage("id");
    assertThatNullPointerException()
        .isThrownBy(
            () -> UpdateBatchDayReplayEntryStatusParam.completedRerun(1L, null, 2L, NOW, NOW))
        .withMessage("toStatus");
    assertThatNullPointerException()
        .isThrownBy(() -> UpdateBatchDayReplayEntryStatusParam.running(1L, 2L, NOW, null))
        .withMessage("now");
  }
}
