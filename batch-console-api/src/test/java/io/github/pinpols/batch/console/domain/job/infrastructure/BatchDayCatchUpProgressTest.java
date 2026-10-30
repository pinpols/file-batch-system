package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批量日补跑逐项状态和未知结果保护")
class BatchDayCatchUpProgressTest {

  @Test
  @DisplayName("逐项认领并确认后才能完成批次")
  void shouldCompleteOnlyAfterEveryItemIsAccepted() {
    var initial = List.of(
        BatchDayCatchUpProgress.Item.pending("A", "key-a"),
        BatchDayCatchUpProgress.Item.pending("B", "key-b"));
    var sendingA = BatchDayCatchUpProgress.claim(initial, "key-a");
    assertThat(BatchDayCatchUpProgress.isComplete(sendingA)).isFalse();
    assertThatThrownBy(() -> BatchDayCatchUpProgress.claim(sendingA, "key-b"))
        .isInstanceOf(IllegalStateException.class);
    var acceptedA = BatchDayCatchUpProgress.accept(sendingA, "key-a", "approval-a");
    var sendingB = BatchDayCatchUpProgress.claim(acceptedA, "key-b");
    var acceptedB = BatchDayCatchUpProgress.accept(sendingB, "key-b", "approval-b");
    assertThat(BatchDayCatchUpProgress.isComplete(acceptedB)).isTrue();
    assertThat(initial.getFirst().status()).isEqualTo(BatchDayCatchUpProgress.ItemStatus.PENDING);
  }

  @Test
  @DisplayName("结果未知时阻止新提交和完成，核实原审批后才能继续")
  void shouldStopOnUnknownUntilOriginalResultIsConfirmed() {
    var pending = List.of(
        BatchDayCatchUpProgress.Item.pending("A", "key-a"),
        BatchDayCatchUpProgress.Item.pending("B", "key-b"));
    var unknown = BatchDayCatchUpProgress.markUnknown(
        BatchDayCatchUpProgress.claim(pending, "key-a"), "key-a");
    assertThat(BatchDayCatchUpProgress.isComplete(unknown)).isFalse();
    assertThatThrownBy(() -> BatchDayCatchUpProgress.claim(unknown, "key-a"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> BatchDayCatchUpProgress.claim(unknown, "key-b"))
        .isInstanceOf(IllegalStateException.class);
    var confirmed = BatchDayCatchUpProgress.accept(unknown, "key-a", "approval-a");
    assertThat(BatchDayCatchUpProgress.claim(confirmed, "key-b").get(1).status())
        .isEqualTo(BatchDayCatchUpProgress.ItemStatus.IN_FLIGHT);
  }

  @Test
  @DisplayName("缺少回执、重复来源键和顺序跳项均拒绝")
  void shouldRejectMissingReceiptDuplicateIdentityAndOutOfOrderClaim() {
    var pending = List.of(
        BatchDayCatchUpProgress.Item.pending("A", "key-a"),
        BatchDayCatchUpProgress.Item.pending("B", "key-b"));
    assertThatThrownBy(() -> BatchDayCatchUpProgress.claim(pending, "key-b"))
        .isInstanceOf(IllegalStateException.class);
    var sending = BatchDayCatchUpProgress.claim(pending, "key-a");
    assertThatThrownBy(() -> BatchDayCatchUpProgress.accept(sending, "key-a", " "))
        .isInstanceOf(IllegalArgumentException.class);
    var duplicate = List.of(
        BatchDayCatchUpProgress.Item.pending("A", "key-a"),
        BatchDayCatchUpProgress.Item.pending("B", "key-a"));
    assertThatThrownBy(() -> BatchDayCatchUpProgress.claim(duplicate, "key-a"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
