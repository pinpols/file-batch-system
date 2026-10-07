package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConsoleAssetFreshnessPolicyMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("资产新鲜度策略服务: 列表上限、默认值补全、更新归一化与参数校验")
class ConsoleAssetFreshnessPolicyServiceTest {

  private ConsoleAssetFreshnessPolicyMapper mapper;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleAssetFreshnessPolicyService service;

  @BeforeEach
  void setUp() {
    mapper = mock(ConsoleAssetFreshnessPolicyMapper.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    service = new ConsoleAssetFreshnessPolicyService(mapper, tenantGuard);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  @DisplayName("查询策略时, 资产编码去除首尾空格且返回条数上限压到允许最大值")
  void shouldListPoliciesWithLimitCap() {
    AssetFreshnessPolicyEntity entity = new AssetFreshnessPolicyEntity(
        1L, "t1", "JOB_A", "JOB", LocalTime.NOON, "Asia/Shanghai", 60, 1, "WARN", true, null, null);
    when(mapper.findByTenant("t1", "JOB_A", true, 500)).thenReturn(List.of(entity));

    List<AssetFreshnessPolicyEntity> result = service.list("t1", " JOB_A ", true, 999);

    assertThat(result)
        .singleElement()
        .extracting(AssetFreshnessPolicyEntity::assetCode)
        .isEqualTo("JOB_A");
  }

  @Test
  @DisplayName("新建作业类策略时, 未填字段按默认值补全后落库")
  void shouldCreateJobPolicyWithDefaults() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("JOB_A")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .build();

    service.upsert(input);

    verify(mapper)
        .upsert(AssetFreshnessPolicyUpsertParam.builder()
            .tenantId("t1")
            .assetCode("JOB_A")
            .assetType("JOB")
            .expectedByLocalTime(LocalTime.of(2, 0))
            .timezone("Asia/Shanghai")
            .staleAfterSeconds(0)
            .lookbackDays(1)
            .severity("WARN")
            .enabled(true)
            .build());
  }

  @Test
  @DisplayName("按标识更新已有策略时, 资产类型与严重级别归一化为大写后落库")
  void shouldUpdateExistingPolicyById() {
    when(mapper.updateById(any())).thenReturn(1);
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .id(9L)
        .tenantId("t1")
        .assetCode("JOB_A")
        .assetType("job")
        .expectedByLocalTime(LocalTime.of(3, 30))
        .timezone("UTC")
        .staleAfterSeconds(300)
        .lookbackDays(2)
        .severity("error")
        .enabled(false)
        .build();

    service.upsert(input);

    verify(mapper)
        .updateById(AssetFreshnessPolicyUpsertParam.builder()
            .id(9L)
            .tenantId("t1")
            .assetCode("JOB_A")
            .assetType("JOB")
            .expectedByLocalTime(LocalTime.of(3, 30))
            .timezone("UTC")
            .staleAfterSeconds(300)
            .lookbackDays(2)
            .severity("ERROR")
            .enabled(false)
            .build());
  }

  @Test
  @DisplayName("资产类型不是作业时, 抛业务异常拒绝写入")
  void shouldRejectNonJobAssetType() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("TABLE_A")
        .assetType("TABLE")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .build();

    assertThatThrownBy(() -> service.upsert(input)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("时区取值非法时, 抛业务异常并提示时区无效")
  void shouldRejectInvalidTimezone() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("JOB_A")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .timezone("Invalid/Zone")
        .build();

    assertThatThrownBy(() -> service.upsert(input))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("timezone is invalid")));
  }

  @Test
  @DisplayName("陈旧判定秒数为负数时, 抛参数非法业务异常")
  void upsert_rejects_whenStaleAfterSecondsNegative() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("JOB_A")
        .assetType("JOB")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .timezone("UTC")
        .staleAfterSeconds(-1)
        .build();

    assertThatThrownBy(() -> service.upsert(input))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("回溯天数低于下限时, 抛参数非法业务异常")
  void upsert_rejects_whenLookbackDaysBelowMin() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("JOB_A")
        .assetType("JOB")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .timezone("UTC")
        .staleAfterSeconds(0)
        .lookbackDays(0)
        .build();

    assertThatThrownBy(() -> service.upsert(input))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("回溯天数超过上限时, 抛参数非法业务异常")
  void upsert_rejects_whenLookbackDaysAboveMax() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("JOB_A")
        .assetType("JOB")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .timezone("UTC")
        .staleAfterSeconds(0)
        .lookbackDays(32)
        .build();

    assertThatThrownBy(() -> service.upsert(input))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("严重级别不在允许取值内时, 抛参数非法业务异常")
  void upsert_rejects_whenSeverityInvalid() {
    AssetFreshnessPolicyUpsertParam input = AssetFreshnessPolicyUpsertParam.builder()
        .tenantId("t1")
        .assetCode("JOB_A")
        .assetType("JOB")
        .expectedByLocalTime(LocalTime.of(2, 0))
        .timezone("UTC")
        .staleAfterSeconds(0)
        .lookbackDays(1)
        .severity("FATAL")
        .build();

    assertThatThrownBy(() -> service.upsert(input))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("启停目标策略不存在时, 抛业务异常表示未找到")
  void shouldReturnNotFoundWhenToggleMissingPolicy() {
    when(mapper.updateEnabled("t1", 9L, true)).thenReturn(0);

    assertThatThrownBy(() -> service.setEnabled("t1", 9L, true)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("按标识查询策略时, 返回对应的资产编码")
  void shouldGetPolicyById() {
    AssetFreshnessPolicyEntity entity = new AssetFreshnessPolicyEntity(
        1L, "t1", "JOB_A", "JOB", LocalTime.NOON, "UTC", 0, 1, "WARN", true, null, null);
    when(mapper.findById("t1", 1L)).thenReturn(Optional.of(entity));

    AssetFreshnessPolicyEntity result = service.get("t1", 1L);

    assertThat(result.assetCode()).isEqualTo("JOB_A");
  }
}
