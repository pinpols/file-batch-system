package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LaunchRequest: 各构造入口与建造器对数据区间, 重放会话和试运行标记的取值约定")
class LaunchRequestTest {

  @Test
  @DisplayName("七参构造: 数据区间与重放会话均为空, 试运行标记默认关闭")
  void shouldDefaultIntervalReplayAndDryRun_whenUsingSevenArgConstructor() {
    LaunchRequest r = new LaunchRequest(
        "t1",
        "JOB_A",
        LocalDate.of(2026, Month.MAY, 7),
        TriggerType.MANUAL,
        "req-1",
        "trace-1",
        Map.of("k", "v"));

    assertThat(r.dataIntervalStart()).isNull();
    assertThat(r.dataIntervalEnd()).isNull();
    assertThat(r.replaySessionId()).isNull();
    assertThat(r.dryRun()).isFalse();
  }

  @Test
  @DisplayName("建造器入口: 数据区间原样保留, 重放会话为空, 显式关闭试运行")
  void shouldPreserveIntervalAndDryRun_whenBuiltWithDataInterval() {
    Instant start = Instant.parse("2026-05-07T00:00:00Z");
    Instant end = Instant.parse("2026-05-08T00:00:00Z");
    LaunchRequest r = LaunchRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .triggerType(TriggerType.SCHEDULED)
        .requestId("req-1")
        .traceId("trace-1")
        .params(Map.of())
        .dataIntervalStart(start)
        .dataIntervalEnd(end)
        .dryRun(false)
        .build();

    assertThat(r.dataIntervalStart()).isEqualTo(start);
    assertThat(r.dataIntervalEnd()).isEqualTo(end);
    assertThat(r.replaySessionId()).isNull();
    assertThat(r.dryRun()).isFalse();
  }

  @Test
  @DisplayName("建造器入口: 重放会话原样保留, 显式关闭试运行")
  void shouldPreserveReplaySessionAndDryRun_whenBuiltWithReplaySession() {
    LaunchRequest r = LaunchRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .triggerType(TriggerType.MANUAL)
        .requestId("req-1")
        .traceId("trace-1")
        .params(Map.of())
        .replaySessionId(42L)
        .dryRun(false)
        .build();

    assertThat(r.replaySessionId()).isEqualTo(42L);
    assertThat(r.dryRun()).isFalse();
  }

  @Test
  @DisplayName("建造器入口: 显式开启试运行时标记为真, 且重放会话仍为空")
  void shouldCarryDryRunFlag_whenBuiltThroughBuilder() {
    LaunchRequest r = LaunchRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .triggerType(TriggerType.MANUAL)
        .requestId("req-1")
        .traceId("trace-1")
        .params(Map.of())
        .dryRun(true)
        .build();

    assertThat(r.dryRun()).isTrue();
    assertThat(r.replaySessionId()).isNull();
  }

  @Test
  @DisplayName("Workflow 简洁入口: 固定 Workflow 目标和业务日, 不生成 Job 目标")
  void shouldPreserveWorkflowIdentityAndBizDate_whenUsingWorkflowConstructor() {
    LocalDate bizDate = LocalDate.of(2026, Month.OCTOBER, 16);
    LaunchRequest request = new LaunchRequest(
        "t1", 42L, bizDate, TriggerType.SCHEDULED, "workflow-request", "trace", Map.of());

    assertThat(request.workflowDefinitionId()).isEqualTo(42L);
    assertThat(request.jobCode()).isNull();
    assertThat(request.bizDate()).isEqualTo(bizDate);
    assertThat(request.requestId()).isEqualTo("workflow-request");
    assertThat(request.dryRun()).isFalse();
  }
}
