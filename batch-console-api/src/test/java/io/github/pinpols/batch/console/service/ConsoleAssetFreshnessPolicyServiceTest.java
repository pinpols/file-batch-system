package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConsoleAssetFreshnessPolicyMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
  void shouldListPoliciesWithLimitCap() {
    AssetFreshnessPolicyEntity entity =
        new AssetFreshnessPolicyEntity(
            1L,
            "t1",
            "JOB_A",
            "JOB",
            LocalTime.NOON,
            "Asia/Shanghai",
            60,
            1,
            "WARN",
            true,
            null,
            null);
    when(mapper.findByTenant("t1", "JOB_A", true, 500)).thenReturn(List.of(entity));

    List<AssetFreshnessPolicyEntity> result = service.list("t1", " JOB_A ", true, 999);

    assertThat(result)
        .singleElement()
        .extracting(AssetFreshnessPolicyEntity::assetCode)
        .isEqualTo("JOB_A");
  }

  @Test
  void shouldCreateJobPolicyWithDefaults() {
    AssetFreshnessPolicyUpsertParam input =
        AssetFreshnessPolicyUpsertParam.builder()
            .tenantId("t1")
            .assetCode("JOB_A")
            .expectedByLocalTime(LocalTime.of(2, 0))
            .build();

    service.upsert(input);

    verify(mapper)
        .upsert(
            AssetFreshnessPolicyUpsertParam.builder()
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
  void shouldUpdateExistingPolicyById() {
    when(mapper.updateById(any())).thenReturn(1);
    AssetFreshnessPolicyUpsertParam input =
        AssetFreshnessPolicyUpsertParam.builder()
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
        .updateById(
            AssetFreshnessPolicyUpsertParam.builder()
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
  void shouldRejectNonJobAssetType() {
    AssetFreshnessPolicyUpsertParam input =
        AssetFreshnessPolicyUpsertParam.builder()
            .tenantId("t1")
            .assetCode("TABLE_A")
            .assetType("TABLE")
            .expectedByLocalTime(LocalTime.of(2, 0))
            .build();

    assertThatThrownBy(() -> service.upsert(input)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldRejectInvalidTimezone() {
    AssetFreshnessPolicyUpsertParam input =
        AssetFreshnessPolicyUpsertParam.builder()
            .tenantId("t1")
            .assetCode("JOB_A")
            .expectedByLocalTime(LocalTime.of(2, 0))
            .timezone("Invalid/Zone")
            .build();

    assertThatThrownBy(() -> service.upsert(input))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex ->
                assertThat(((BizException) ex).getMessageArgs())
                    .anyMatch(a -> a != null && a.toString().contains("timezone is invalid")));
  }

  @Test
  void shouldReturnNotFoundWhenToggleMissingPolicy() {
    when(mapper.updateEnabled("t1", 9L, true)).thenReturn(0);

    assertThatThrownBy(() -> service.setEnabled("t1", 9L, true)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldGetPolicyById() {
    AssetFreshnessPolicyEntity entity =
        new AssetFreshnessPolicyEntity(
            1L, "t1", "JOB_A", "JOB", LocalTime.NOON, "UTC", 0, 1, "WARN", true, null, null);
    when(mapper.findById("t1", 1L)).thenReturn(Optional.of(entity));

    AssetFreshnessPolicyEntity result = service.get("t1", 1L);

    assertThat(result.assetCode()).isEqualTo("JOB_A");
  }
}
