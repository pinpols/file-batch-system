package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LaunchParamResolverTest {

  private LaunchParamResolver resolver;

  @BeforeEach
  void setUp() {
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    resolver =
        new LaunchParamResolver(
            timezoneProvider, new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider));
  }

  @Test
  void shouldResolveBatchNoFromParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("batchNo", "B001");

    String result = resolver.resolveBatchNo(LocalDate.of(2026, 4, 10), params);

    assertThat(result).isEqualTo("B001");
  }

  @Test
  void shouldFallbackBatchNoToBizDate() {
    Map<String, Object> params = new HashMap<>();

    String result = resolver.resolveBatchNo(LocalDate.of(2026, 4, 10), params);

    assertThat(result).isEqualTo("2026-04-10");
  }

  @Test
  void shouldResolveOperatorId() {
    Map<String, Object> params = new HashMap<>();
    params.put("operatorId", "user1");

    String result = LaunchParamResolver.resolveOperatorId(params);

    assertThat(result).isEqualTo("user1");
  }

  @Test
  void shouldResolveRerunFlagFromParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("rerunFlag", true);

    boolean result = resolver.resolveRerunFlag(TriggerType.MANUAL, params);

    assertThat(result).isTrue();
  }

  @Test
  void shouldResolveRerunFlagFromCatchUpTrigger() {
    Map<String, Object> params = new HashMap<>();

    boolean result = resolver.resolveRerunFlag(TriggerType.CATCH_UP, params);

    assertThat(result).isTrue();
  }

  @Test
  void shouldResolveRetryFlagFromParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("retryFlag", true);

    boolean result = resolver.resolveRetryFlag(params);

    assertThat(result).isTrue();
  }

  @Test
  void shouldResolveRunModeNormal() {
    Map<String, Object> params = new HashMap<>();

    RunMode result = resolver.resolveRunMode(TriggerType.MANUAL, params);

    assertThat(result).isEqualTo(RunMode.NORMAL);
  }

  @Test
  void shouldResolveRunModeCompensate() {
    Map<String, Object> params = new HashMap<>();
    params.put("operationType", "COMPENSATE");

    RunMode result = resolver.resolveRunMode(TriggerType.MANUAL, params);

    assertThat(result).isEqualTo(RunMode.COMPENSATE);
  }

  @Test
  void shouldParseDeadlineFromInstantString() {
    Instant result = resolver.parseDeadlineInstant("2026-04-10T12:00:00Z", null);

    assertThat(result).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
  }

  @Test
  void shouldReturnNullForNullDeadline() {
    Instant result = resolver.parseDeadlineInstant(null, null);

    assertThat(result).isNull();
  }

  @Test
  void shouldFindEarliestInstant() {
    Instant earlier = Instant.parse("2026-04-10T10:00:00Z");
    Instant later = Instant.parse("2026-04-10T14:00:00Z");

    Instant result = resolver.earliest(earlier, later);

    assertThat(result).isEqualTo(earlier);
  }

  @Test
  void shouldConvertToBoolean() {
    assertThat(LaunchParamResolver.toBoolean(true)).isTrue();
    assertThat(LaunchParamResolver.toBoolean("true")).isTrue();
    assertThat(LaunchParamResolver.toBoolean("1")).isTrue();
    assertThat(LaunchParamResolver.toBoolean("Y")).isTrue();
    assertThat(LaunchParamResolver.toBoolean(false)).isFalse();
    assertThat(LaunchParamResolver.toBoolean("no")).isFalse();
  }

  @Test
  void shouldExtractTextValue() {
    assertThat(LaunchParamResolver.textValue(null)).isNull();
    assertThat(LaunchParamResolver.textValue(" hello ")).isEqualTo("hello");
    assertThat(LaunchParamResolver.textValue("")).isNull();
  }

  @Test
  void shouldSafeIncrement() {
    assertThat(LaunchParamResolver.safeIncrement(null)).isEqualTo(1);
    assertThat(LaunchParamResolver.safeIncrement(5)).isEqualTo(6);
  }
}
