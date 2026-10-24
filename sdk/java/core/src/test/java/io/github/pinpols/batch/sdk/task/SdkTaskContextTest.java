package io.github.pinpols.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SdkTaskContext — 调度上下文透传、取消信号与进度上报")
class SdkTaskContextTest {

  @Test
  @DisplayName("handler 可从 CLAIM 冻结快照读取精确跨日结果版本")
  void shouldReadPinnedCrossDayResultFromClaimParameters() {
    SdkTaskContext ctx = new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        42L,
        "w1",
        Map.of(
            "crossDay",
            Map.of(
                "prior_day",
                Map.of(
                    "resultVersionId",
                    701L,
                    "versionNo",
                    4,
                    "businessKey",
                    "job:DAILY_PNL:2026-10-16",
                    "jobInstanceId",
                    601L,
                    "payloadStorage",
                    "INLINE_JSON",
                    "output",
                    Map.of("recordCount", 17, "sourceBatch", "previous-day")))),
        Map.of());

    SdkCrossDayResult result = ctx.crossDayResults("prior_day").getFirst();

    assertThat(result.resultVersionId()).isEqualTo(701L);
    assertThat(result.versionNo()).isEqualTo(4);
    assertThat(result.businessKey()).isEqualTo("job:DAILY_PNL:2026-10-16");
    assertThat(result.output())
        .containsEntry("recordCount", 17)
        .containsEntry("sourceBatch", "previous-day");
    assertThatThrownBy(() -> result.output().put("recordCount", 99))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("跨日输出的嵌套对象和数组也保持只读")
  @SuppressWarnings("unchecked")
  void shouldDeepFreezeCrossDayOutputSnapshot() {
    Map<String, Object> entry = Map.of(
        "resultVersionId",
        701L,
        "versionNo",
        1,
        "businessKey",
        "job:DAILY_PNL:2026-10-16",
        "payloadStorage",
        "INLINE_JSON",
        "output",
        Map.of(
            "metrics", new LinkedHashMap<>(Map.of("recordCount", 17)),
            "records", new ArrayList<>(List.of("a", "b"))));
    SdkTaskContext ctx = new SdkTaskContext(
        "t1", "job-1", "ti-1", 42L, "w1", Map.of("crossDay", Map.of("prior_day", entry)), Map.of());

    Map<?, ?> metrics =
        (Map<?, ?>) ctx.crossDayResults("prior_day").getFirst().output().get("metrics");
    List<?> records =
        (List<?>) ctx.crossDayResults("prior_day").getFirst().output().get("records");

    assertThatThrownBy(() -> ((Map<Object, Object>) metrics).put("recordCount", 99))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<Object>) records).add("c"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("范围依赖解析为多个固定版本且缺失可选别名返回空列表")
  void shouldReadRangeSnapshotsAndAllowMissingOptionalAlias() {
    Map<String, Object> first = Map.of(
        "resultVersionId",
        701L,
        "versionNo",
        1,
        "businessKey",
        "job:DAILY_PNL:2026-10-15",
        "payloadStorage",
        "INLINE_JSON",
        "output",
        Map.of("recordCount", 10));
    Map<String, Object> second = Map.of(
        "resultVersionId",
        702L,
        "versionNo",
        2,
        "businessKey",
        "job:DAILY_PNL:2026-10-16",
        "payloadStorage",
        "INLINE_JSON",
        "output",
        Map.of("recordCount", 17));
    SdkTaskContext ctx = new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        42L,
        "w1",
        Map.of("crossDay", Map.of("prior_days", Map.of("outputs", List.of(first, second)))),
        Map.of());

    assertThat(ctx.crossDayResults("prior_days"))
        .extracting(SdkCrossDayResult::resultVersionId)
        .containsExactly(701L, 702L);
    assertThat(ctx.crossDayResults("optional_missing")).isEmpty();
  }

  @Test
  @DisplayName("不支持的跨日存储或缺失版本身份失败关闭")
  void shouldFailClosedForUnsupportedOrUnpinnedCrossDayPayload() {
    SdkTaskContext external = new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        42L,
        "w1",
        Map.of("crossDay", Map.of("prior_day", Map.of("payloadStorage", "EXTERNAL_REF"))),
        Map.of());
    SdkTaskContext missingIdentity = new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        43L,
        "w1",
        Map.of(
            "crossDay",
            Map.of(
                "prior_day",
                Map.of(
                    "versionNo",
                    1,
                    "businessKey",
                    "job:DAILY_PNL:2026-10-16",
                    "payloadStorage",
                    "INLINE_JSON",
                    "output",
                    Map.of("recordCount", 17)))),
        Map.of());

    assertThatThrownBy(() -> external.crossDayResults("prior_day"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported cross-day payload storage");
    assertThatThrownBy(() -> missingIdentity.crossDayResults("prior_day"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resultVersionId");
  }

  @Test
  @DisplayName("非整数或超出 long 范围的版本身份失败关闭")
  void shouldRejectNonIntegralOrOutOfRangeCrossDayIdentity() {
    SdkTaskContext fractional = new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        42L,
        "w1",
        Map.of(
            "crossDay",
            Map.of(
                "prior_day",
                Map.of(
                    "resultVersionId",
                    new BigDecimal("701.5"),
                    "versionNo",
                    1,
                    "businessKey",
                    "job:DAILY_PNL:2026-10-16",
                    "payloadStorage",
                    "INLINE_JSON",
                    "output",
                    Map.of()))),
        Map.of());
    SdkTaskContext outOfRange = new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        43L,
        "w1",
        Map.of(
            "crossDay",
            Map.of(
                "prior_day",
                Map.of(
                    "resultVersionId",
                    new BigInteger("9223372036854775808"),
                    "versionNo",
                    1,
                    "businessKey",
                    "job:DAILY_PNL:2026-10-16",
                    "payloadStorage",
                    "INLINE_JSON",
                    "output",
                    Map.of()))),
        Map.of());

    assertThatThrownBy(() -> fractional.crossDayResults("prior_day"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be an integer");
    assertThatThrownBy(() -> outOfRange.crossDayResults("prior_day"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be an integer");
  }

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
