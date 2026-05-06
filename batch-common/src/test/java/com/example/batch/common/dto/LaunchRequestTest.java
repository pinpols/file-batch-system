package com.example.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.TriggerType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LaunchRequestTest {

  @Test
  void sevenArgConstructorDefaultsIntervalReplayDryRunToFalsy() {
    LaunchRequest r =
        new LaunchRequest(
            "t1",
            "JOB_A",
            LocalDate.of(2026, 5, 7),
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
  void nineArgConstructorPreservesIntervalAndDefaultsReplayAndDryRun() {
    Instant start = Instant.parse("2026-05-07T00:00:00Z");
    Instant end = Instant.parse("2026-05-08T00:00:00Z");
    LaunchRequest r =
        new LaunchRequest(
            "t1",
            "JOB_A",
            LocalDate.of(2026, 5, 7),
            TriggerType.SCHEDULED,
            "req-1",
            "trace-1",
            Map.of(),
            start,
            end);

    assertThat(r.dataIntervalStart()).isEqualTo(start);
    assertThat(r.dataIntervalEnd()).isEqualTo(end);
    assertThat(r.replaySessionId()).isNull();
    assertThat(r.dryRun()).isFalse();
  }

  @Test
  void tenArgConstructorPreservesReplaySessionAndDefaultsDryRun() {
    LaunchRequest r =
        new LaunchRequest(
            "t1",
            "JOB_A",
            LocalDate.of(2026, 5, 7),
            TriggerType.MANUAL,
            "req-1",
            "trace-1",
            Map.of(),
            null,
            null,
            42L);

    assertThat(r.replaySessionId()).isEqualTo(42L);
    assertThat(r.dryRun()).isFalse();
  }

  @Test
  void builderCarriesDryRunFlag() {
    LaunchRequest r =
        LaunchRequest.builder()
            .tenantId("t1")
            .jobCode("JOB_A")
            .bizDate(LocalDate.of(2026, 5, 7))
            .triggerType(TriggerType.MANUAL)
            .requestId("req-1")
            .traceId("trace-1")
            .params(Map.of())
            .dryRun(true)
            .build();

    assertThat(r.dryRun()).isTrue();
    assertThat(r.replaySessionId()).isNull();
  }
}
