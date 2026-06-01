package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.CustomTaskTypeRegistryEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LaunchParamResolverTest {

  @Mock private CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper;

  private LaunchParamResolver resolver;

  @BeforeEach
  void setUp() {
    lenient()
        .when(customTaskTypeRegistryMapper.selectByTenantAndCode(anyString(), anyString()))
        .thenReturn(null);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    resolver =
        new LaunchParamResolver(
            timezoneProvider,
            new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
            customTaskTypeRegistryMapper);
  }

  private CustomTaskTypeRegistryEntity descriptorEntity(String descriptorJson) {
    return new CustomTaskTypeRegistryEntity(
        1L,
        "ta",
        "tenant_ta_import",
        "导入",
        descriptorJson,
        "v1",
        "SDK_DECLARED",
        "w1",
        "ACTIVE",
        null,
        null,
        null,
        null);
  }

  private JobDefinitionEntity jobDef(String jobType, Map<String, Object> defaultParams) {
    return JobDefinitionEntity.builder()
        .id(1L)
        .tenantId("ta")
        .jobCode("J1")
        .jobType(jobType)
        .defaultParams(defaultParams)
        .version(1)
        .build();
  }

  private LaunchRequest launchRequest(Map<String, Object> params, LocalDate bizDate) {
    return LaunchRequest.builder()
        .tenantId("ta")
        .jobCode("J1")
        .bizDate(bizDate)
        .triggerType(TriggerType.MANUAL)
        .params(params)
        .build();
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

  // ===== mergeLaunchParams: descriptor.defaults 注入 + 优先级 (SDK Phase 3 M3.1) =====

  @Test
  void shouldMergeDescriptorDefaultsAsLowestPriority() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(
            descriptorEntity(
                "{\"code\":\"tenant_ta_import\",\"defaults\":{\"batchSize\":500,\"region\":\"cn\"}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of("region", "us"));
    LaunchRequest request = launchRequest(Map.of("timeout", 30), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("batchSize", 500);
    assertThat(merged).containsEntry("region", "us"); // defaultParams 覆盖 descriptor.defaults
    assertThat(merged).containsEntry("timeout", 30);
  }

  @Test
  void shouldLetRequestParamsOverrideDescriptorDefaults() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{\"defaults\":{\"batchSize\":500}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of());
    LaunchRequest request = launchRequest(Map.of("batchSize", 999), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("batchSize", 999);
  }

  @Test
  void shouldSubstituteTemplateVariablesInDescriptorDefaults() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{\"defaults\":{\"path\":\"/data/${bizDate}/in\"}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of());
    LaunchRequest request = launchRequest(Map.of(), LocalDate.of(2026, 6, 1));

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("path", "/data/2026-06-01/in");
  }

  @Test
  void shouldKeepUnknownTemplateTokenAsIs() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{\"defaults\":{\"path\":\"/data/${unknownVar}/in\"}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of());
    LaunchRequest request = launchRequest(Map.of(), LocalDate.of(2026, 6, 1));

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("path", "/data/${unknownVar}/in");
  }

  @Test
  void shouldSkipDescriptorDefaultsWhenJobTypeNotRegistered() {
    JobDefinitionEntity jobDef = jobDef("file-import", Map.of("region", "cn"));
    LaunchRequest request = launchRequest(Map.of(), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("region", "cn");
    assertThat(merged).doesNotContainKey("batchSize");
  }

  @Test
  void shouldNotFailWhenDescriptorJsonMalformed() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{not-json"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of("region", "cn"));
    LaunchRequest request = launchRequest(Map.of(), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("region", "cn");
  }
}
