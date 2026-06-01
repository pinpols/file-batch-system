package com.example.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkTaskContextTest {

  @Test
  void schedulingGettersDelegateToContext() {
    SdkSchedulingContext sc =
        new SdkSchedulingContext(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 5, 29),
            LocalDate.of(2026, 6, 2),
            false,
            2,
            "SCHEDULED",
            null,
            null);
    SdkTaskContext ctx =
        new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of(), sc);

    assertThat(ctx.bizDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(ctx.prevBizDate()).isEqualTo(LocalDate.of(2026, 5, 29));
    assertThat(ctx.nextBizDate()).isEqualTo(LocalDate.of(2026, 6, 2));
    assertThat(ctx.isHoliday()).isFalse();
    assertThat(ctx.attemptNo()).isEqualTo(2);
    assertThat(ctx.triggerCode()).isNull();
    assertThat(ctx.workflowRunId()).isNull();
    assertThat(ctx.schedulingContext().triggerType()).isEqualTo("SCHEDULED");
  }

  @Test
  void schedulingGettersNullSafeWhenContextAbsent() {
    // 老平台不下发 schedulingContext → 7 参兼容构造,getter 一律返回 null 而非 NPE
    SdkTaskContext ctx = new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of());

    assertThat(ctx.schedulingContext()).isNull();
    assertThat(ctx.bizDate()).isNull();
    assertThat(ctx.prevBizDate()).isNull();
    assertThat(ctx.nextBizDate()).isNull();
    assertThat(ctx.isHoliday()).isNull();
    assertThat(ctx.attemptNo()).isNull();
    assertThat(ctx.triggerCode()).isNull();
    assertThat(ctx.workflowRunId()).isNull();
  }

  @Test
  void isCancelledReflectsSignal() {
    CancellationSignal signal = new CancellationSignal();
    SdkTaskContext ctx =
        new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of(), null, signal);

    assertThat(ctx.isCancelled()).isFalse();
    signal.cancel();
    assertThat(ctx.isCancelled()).isTrue();
  }

  @Test
  void isCancelledFalseWhenNoSignalSupplied() {
    // 兼容构造未传信号 → 构造器补空信号,isCancelled() 返回 false 而非 NPE
    SdkTaskContext ctx = new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of());

    assertThat(ctx.isCancelled()).isFalse();
  }
}
