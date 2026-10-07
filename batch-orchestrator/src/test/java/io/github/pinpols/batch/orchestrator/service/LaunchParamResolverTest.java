package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.CustomTaskTypeRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("启动参数解析:批次号取值与回退,运行模式判定,截止时间解析,以及描述符默认参数的合并优先级")
class LaunchParamResolverTest {

  @Mock
  private CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper;

  private LaunchParamResolver resolver;

  @BeforeEach
  void setUp() {
    lenient()
        .when(customTaskTypeRegistryMapper.selectByTenantAndCode(anyString(), anyString()))
        .thenReturn(null);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    resolver = new LaunchParamResolver(
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
  @DisplayName("参数中显式提供批次号时优先采用参数值")
  void shouldResolveBatchNoFromParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("batchNo", "B001");

    String result = resolver.resolveBatchNo(LocalDate.of(2026, Month.APRIL, 10), params);

    assertThat(result).isEqualTo("B001");
  }

  @Test
  @DisplayName("参数未提供批次号时回退为业务日期文本")
  void shouldFallbackBatchNoToBizDate() {
    Map<String, Object> params = new HashMap<>();

    String result = resolver.resolveBatchNo(LocalDate.of(2026, Month.APRIL, 10), params);

    assertThat(result).isEqualTo("2026-04-10");
  }

  @Test
  @DisplayName("从参数中取到操作人标识")
  void shouldResolveOperatorId() {
    Map<String, Object> params = new HashMap<>();
    params.put("operatorId", "user1");

    String result = LaunchParamResolver.resolveOperatorId(params);

    assertThat(result).isEqualTo("user1");
  }

  @Test
  @DisplayName("参数显式声明重跑标记时判定为重跑")
  void shouldResolveRerunFlagFromParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("rerunFlag", true);

    boolean result = resolver.resolveRerunFlag(TriggerType.MANUAL, params);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("补跑触发类型即使未带参数也判定为重跑")
  void shouldResolveRerunFlagFromCatchUpTrigger() {
    Map<String, Object> params = new HashMap<>();

    boolean result = resolver.resolveRerunFlag(TriggerType.CATCH_UP, params);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("参数显式声明重试标记时判定为重试")
  void shouldResolveRetryFlagFromParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("retryFlag", true);

    boolean result = resolver.resolveRetryFlag(params);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("手工触发且未声明补偿操作时运行模式为普通")
  void shouldResolveRunModeNormal() {
    Map<String, Object> params = new HashMap<>();

    RunMode result = resolver.resolveRunMode(TriggerType.MANUAL, params);

    assertThat(result).isEqualTo(RunMode.NORMAL);
  }

  @Test
  @DisplayName("参数声明补偿操作时运行模式为补偿")
  void shouldResolveRunModeCompensate() {
    Map<String, Object> params = new HashMap<>();
    params.put("operationType", "COMPENSATE");

    RunMode result = resolver.resolveRunMode(TriggerType.MANUAL, params);

    assertThat(result).isEqualTo(RunMode.COMPENSATE);
  }

  @Test
  @DisplayName("截止时间传入标准时间戳文本时解析出对应时间点")
  void shouldParseDeadlineFromInstantString() {
    Instant result = resolver.parseDeadlineInstant("2026-04-10T12:00:00Z", null);

    assertThat(result).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
  }

  @Test
  @DisplayName("截止时间为空时返回空值而不报错")
  void shouldReturnNullForNullDeadline() {
    Instant result = resolver.parseDeadlineInstant(null, null);

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("两个时间点比较时取更早的一个")
  void shouldFindEarliestInstant() {
    Instant earlier = Instant.parse("2026-04-10T10:00:00Z");
    Instant later = Instant.parse("2026-04-10T14:00:00Z");

    Instant result = resolver.earliest(earlier, later);

    assertThat(result).isEqualTo(earlier);
  }

  @Test
  @DisplayName("布尔真值与常见真值文本识别为真,布尔假值与否定文本识别为假")
  void shouldConvertToBoolean() {
    assertThat(LaunchParamResolver.toBoolean(true)).isTrue();
    assertThat(LaunchParamResolver.toBoolean("true")).isTrue();
    assertThat(LaunchParamResolver.toBoolean("1")).isTrue();
    assertThat(LaunchParamResolver.toBoolean("Y")).isTrue();
    assertThat(LaunchParamResolver.toBoolean(false)).isFalse();
    assertThat(LaunchParamResolver.toBoolean("no")).isFalse();
  }

  @Test
  @DisplayName("文本值去除首尾空白后返回,空值或全空白返回空")
  void shouldExtractTextValue() {
    assertThat(LaunchParamResolver.textValue(null)).isNull();
    assertThat(LaunchParamResolver.textValue(" hello ")).isEqualTo("hello");
    assertThat(LaunchParamResolver.textValue("")).isNull();
  }

  @Test
  @DisplayName("计数自增:入参为空时按起步值计算,否则在入参基础上加一")
  void shouldSafeIncrement() {
    assertThat(LaunchParamResolver.safeIncrement(null)).isEqualTo(1);
    assertThat(LaunchParamResolver.safeIncrement(5)).isEqualTo(6);
  }

  // ===== mergeLaunchParams: descriptor.defaults 注入 + 优先级 (SDK Phase 3 M3.1) =====

  @Test
  @DisplayName("描述符默认参数优先级最低,作业默认参数与请求参数依次覆盖")
  void shouldMergeDescriptorDefaultsAsLowestPriority() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity(
            "{\"code\":\"tenant_ta_import\",\"defaults\":{\"batchSize\":500,\"region\":\"cn\"}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of("region", "us"));
    LaunchRequest request = launchRequest(Map.of("timeout", 30), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("batchSize", 500);
    assertThat(merged).containsEntry("region", "us"); // defaultParams 覆盖 descriptor.defaults
    assertThat(merged).containsEntry("timeout", 30);
  }

  @Test
  @DisplayName("请求参数覆盖描述符默认参数")
  void shouldLetRequestParamsOverrideDescriptorDefaults() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{\"defaults\":{\"batchSize\":500}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of());
    LaunchRequest request = launchRequest(Map.of("batchSize", 999), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("batchSize", 999);
  }

  @Test
  @DisplayName("描述符默认参数中的模板变量按业务日期替换")
  void shouldSubstituteTemplateVariablesInDescriptorDefaults() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{\"defaults\":{\"path\":\"/data/${bizDate}/in\"}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of());
    LaunchRequest request = launchRequest(Map.of(), LocalDate.of(2026, Month.JUNE, 1));

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("path", "/data/2026-06-01/in");
  }

  @Test
  @DisplayName("描述符默认参数中的未知模板变量原样保留")
  void shouldKeepUnknownTemplateTokenAsIs() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{\"defaults\":{\"path\":\"/data/${unknownVar}/in\"}}"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of());
    LaunchRequest request = launchRequest(Map.of(), LocalDate.of(2026, Month.JUNE, 1));

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("path", "/data/${unknownVar}/in");
  }

  @Test
  @DisplayName("作业类型未注册描述符时跳过默认参数注入,仅保留作业自身默认参数")
  void shouldSkipDescriptorDefaultsWhenJobTypeNotRegistered() {
    JobDefinitionEntity jobDef = jobDef("file-import", Map.of("region", "cn"));
    LaunchRequest request = launchRequest(Map.of(), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("region", "cn").doesNotContainKey("batchSize");
  }

  @Test
  @DisplayName("描述符内容非法时忽略默认参数注入且不影响作业自身默认参数")
  void shouldNotFailWhenDescriptorJsonMalformed() {
    when(customTaskTypeRegistryMapper.selectByTenantAndCode("ta", "tenant_ta_import"))
        .thenReturn(descriptorEntity("{not-json"));
    JobDefinitionEntity jobDef = jobDef("tenant_ta_import", Map.of("region", "cn"));
    LaunchRequest request = launchRequest(Map.of(), null);

    Map<String, Object> merged = resolver.mergeLaunchParams(jobDef, request);

    assertThat(merged).containsEntry("region", "cn");
  }
}
