package io.github.pinpols.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SdkTaskContext — 调度上下文透传、取消信号与进度上报")
class SdkTaskContextTest {

  @Test
  @DisplayName("调度上下文各字段经 getter 原样透出")
  void shouldDelegateSchedulingGetters_whenContextProvided() {
    SdkSchedulingContext sc = new SdkSchedulingContext(
        LocalDate.of(2026, Month.JUNE, 1),
        LocalDate.of(2026, Month.MAY, 29),
        LocalDate.of(2026, Month.JUNE, 2),
        false,
        2,
        "SCHEDULED",
        null,
        null);
    SdkTaskContext ctx =
        new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of(), sc);

    assertThat(ctx.bizDate()).isEqualTo(LocalDate.of(2026, Month.JUNE, 1));
    assertThat(ctx.prevBizDate()).isEqualTo(LocalDate.of(2026, Month.MAY, 29));
    assertThat(ctx.nextBizDate()).isEqualTo(LocalDate.of(2026, Month.JUNE, 2));
    assertThat(ctx.isHoliday()).isFalse();
    assertThat(ctx.attemptNo()).isEqualTo(2);
    assertThat(ctx.triggerCode()).isNull();
    assertThat(ctx.workflowRunId()).isNull();
    assertThat(ctx.schedulingContext().triggerType()).isEqualTo("SCHEDULED");
  }

  @Test
  @DisplayName("旧版平台不下发调度上下文时 getter 返回 null 而非报错")
  void shouldReturnNull_whenSchedulingContextAbsent() {
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
  @DisplayName("取消标记跟随外部信号翻转")
  void shouldReflectSignal_whenCancellationToggled() {
    CancellationSignal signal = new CancellationSignal();
    SdkTaskContext ctx =
        new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of(), null, signal);

    assertThat(ctx.isCancelled()).isFalse();
    signal.cancel();
    assertThat(ctx.isCancelled()).isTrue();
  }

  @Test
  @DisplayName("未提供取消信号时按未取消处理且不报错")
  void shouldReportNotCancelled_whenNoSignalSupplied() {
    // 兼容构造未传信号 → 构造器补空信号,isCancelled() 返回 false 而非 NPE
    SdkTaskContext ctx = new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of());

    assertThat(ctx.isCancelled()).isFalse();
  }

  @Test
  @DisplayName("运行时属性优先于兼容参数,两者都能识别试运行")
  void shouldResolveDryRun_whenRuntimeAttributeAndLegacyParameterDiffer() {
    SdkTaskContext runtime = new SdkTaskContext(
        "t1", "job-1", "ti-1", 42L, "w1", Map.of("dryRun", false), Map.of("dryRun", true));
    SdkTaskContext legacy =
        new SdkTaskContext("t1", "job-1", "ti-1", 43L, "w1", Map.of("dryRun", "true"), Map.of());

    assertThat(runtime.isDryRun()).isTrue();
    assertThat(legacy.isDryRun()).isTrue();
  }

  @Test
  @DisplayName("多次上报只保留最近一次进度快照")
  void shouldKeepLatestSnapshot_whenProgressReportedRepeatedly() {
    ProgressReporter reporter = new ProgressReporter();
    SdkTaskContext ctx = new SdkTaskContext(
        "t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of(), null, null, reporter);

    assertThat(reporter.latest()).isNull();
    ctx.reportProgress(Map.of("processed", 10, "total", 100));
    assertThat(reporter.latest()).containsEntry("processed", 10).containsEntry("total", 100);

    // 最新值覆盖语义:再报一次只留最近一次
    ctx.reportProgress(Map.of("processed", 50, "total", 100));
    assertThat(reporter.latest()).containsEntry("processed", 50);
  }

  @Test
  @DisplayName("未提供上报器时补空实现,上报不报错且可读回")
  void shouldNotFail_whenReporterAbsent() {
    // 兼容构造未传 reporter → 构造器补空槽,reportProgress 不 NPE
    SdkTaskContext ctx = new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", Map.of(), Map.of());

    ctx.reportProgress(Map.of("processed", 1));
    assertThat(ctx.progress().latest()).containsEntry("processed", 1);
  }
}
