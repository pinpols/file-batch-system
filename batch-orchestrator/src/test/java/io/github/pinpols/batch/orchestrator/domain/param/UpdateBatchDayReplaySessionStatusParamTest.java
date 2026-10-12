package io.github.pinpols.batch.orchestrator.domain.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批次日重放会话状态更新参数:合法状态约束")
class UpdateBatchDayReplaySessionStatusParamTest {

  private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

  @Test
  @DisplayName("具名工厂固定审批与完成状态的字段")
  void shouldBuildNamedStateShapes() {
    UpdateBatchDayReplaySessionStatusParam approved =
        UpdateBatchDayReplaySessionStatusParam.approved(
            "t1", 1L, List.of("PENDING_APPROVAL"), "admin", NOW);
    UpdateBatchDayReplaySessionStatusParam completed =
        UpdateBatchDayReplaySessionStatusParam.completed(
            "t1", 1L, "SUCCEEDED", List.of("RUNNING"), NOW);

    assertThat(approved.getToStatus()).isEqualTo("RUNNING");
    assertThat(approved.getStartedAt()).isEqualTo(NOW);
    assertThat(approved.getCompletedAt()).isNull();
    assertThat(completed.getToStatus()).isEqualTo("SUCCEEDED");
    assertThat(completed.getStartedAt()).isNull();
    assertThat(completed.getCompletedAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("必填字段缺失时拒绝构造")
  void shouldRejectMissingRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> UpdateBatchDayReplaySessionStatusParam.approved(
            null, 1L, List.of("PENDING_APPROVAL"), "admin", NOW))
        .withMessage("tenantId");
    assertThatNullPointerException()
        .isThrownBy(() ->
            UpdateBatchDayReplaySessionStatusParam.completed("t1", 1L, "SUCCEEDED", null, NOW))
        .withMessage("expectedStatuses");
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            UpdateBatchDayReplaySessionStatusParam.completed("t1", 1L, "SUCCEEDED", List.of(), NOW))
        .withMessage("expectedStatuses must not be empty");
  }
}
