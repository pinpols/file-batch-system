package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiMonthlyUsageMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 成本服务:预算预留、结算与供应商定价校验")
class ConsoleAiCostServiceTest {

  @Mock
  private ConsoleAiMonthlyUsageMapper mapper;

  private ConsoleAiProperties properties;
  private ConsoleAiCostService service;

  @BeforeEach
  void setUp() {
    properties = new ConsoleAiProperties();
    properties.setMaxCompletionTokens(100);
    properties.getCost().setMonthlyBudgetUsd(new BigDecimal("10.00"));
    properties.getCost().setProviderRates(new LinkedHashMap<>());
    ConsoleAiProperties.ProviderRate rate = new ConsoleAiProperties.ProviderRate();
    rate.setInputUsdPerMillionTokens(new BigDecimal("2.00"));
    rate.setOutputUsdPerMillionTokens(new BigDecimal("8.00"));
    properties.getCost().getProviderRates().put("test-provider", rate);
    service = new ConsoleAiCostService(mapper, properties);
  }

  @Test
  @DisplayName("成本预留:调用模型前先预留月度预算,并释放过期预留")
  void shouldReserveMonthlyBudgetBeforeProviderCall() {
    when(mapper.reserve(eq("tenant-a"), any(), any(), eq(new BigDecimal("10.00"))))
        .thenReturn(true);

    ConsoleAiCostService.Reservation reservation = service.reserve("tenant-a", "hello", "system");

    assertThat(reservation.budgetReserved()).isTrue();
    assertThat(reservation.reservedAmount()).isPositive();
    verify(mapper).setTenantContext("tenant-a");
    verify(mapper)
        .releaseStaleReservations(eq("tenant-a"), any(LocalDate.class), any(OffsetDateTime.class));
  }

  @Test
  @DisplayName("月度预算原子预留失败:直接拒绝本次请求")
  void shouldRejectWhenAtomicMonthlyBudgetReservationFails() {
    when(mapper.reserve(eq("tenant-a"), any(), any(), eq(new BigDecimal("10.00"))))
        .thenReturn(false);

    assertThatThrownBy(() -> service.reserve("tenant-a", "hello", "system"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("成本结算:按供应商上报的输入输出令牌用量计价入账")
  void shouldSettleUsingProviderReportedTokenUsage() {
    ConsoleAiCostService.Reservation reservation = new ConsoleAiCostService.Reservation(
        "tenant-a", YearMonth.now(ZoneOffset.UTC).atDay(1), new BigDecimal("0.01"), true);

    ConsoleAiCostService.CostResult result =
        service.settle(reservation, "test-provider", 1000, 500);

    assertThat(result.status()).isEqualTo("PRICED");
    assertThat(result.amount()).isEqualByComparingTo("0.00600000");
    verify(mapper)
        .settle(
            "tenant-a",
            reservation.billingMonth(),
            reservation.reservedAmount(),
            result.amount(),
            1000,
            500);
  }

  @Test
  @DisplayName("令牌用量缺失:按预留金额保守结算")
  void shouldConservativelySettleReservationWhenUsageIsMissing() {
    ConsoleAiCostService.Reservation reservation = new ConsoleAiCostService.Reservation(
        "tenant-a", YearMonth.now(ZoneOffset.UTC).atDay(1), new BigDecimal("0.01"), true);

    ConsoleAiCostService.CostResult result = service.settle(reservation, null, null, null);

    assertThat(result.status()).isEqualTo("RESERVED");
    assertThat(result.amount()).isEqualByComparingTo("0.01");
  }

  @Test
  @DisplayName("图片输入:预留金额高于纯文本输入")
  void shouldReserveAdditionalBudgetForImageInput() {
    when(mapper.reserve(eq("tenant-a"), any(), any(), any())).thenReturn(true);

    ConsoleAiCostService.Reservation text = service.reserve("tenant-a", "hello", "system", 0);
    ConsoleAiCostService.Reservation image = service.reserve("tenant-a", "hello", "system", 1);

    assertThat(image.reservedAmount()).isGreaterThan(text.reservedAmount());
  }

  @Test
  @DisplayName("图片输入缺少可用定价:拒绝请求,且不使用无上限预留")
  void shouldRejectImageInputWithoutUsablePricing() {
    properties.getCost().getProviderRates().clear();
    properties.getCost().setMonthlyBudgetUsd(BigDecimal.ZERO);

    assertThatThrownBy(() -> service.reserve("tenant-a", "hello", "system", 1))
        .isInstanceOf(BizException.class);
    verify(mapper, never()).reserveUnbounded(anyString(), any(), any());
  }

  @Test
  @DisplayName("供应商定价校验:存在缺费率的供应商时触发拒绝")
  void shouldRequireRatesForEveryConfiguredProvider() {
    assertThatThrownBy(() -> service.validateProviders(List.of("test-provider", "unpriced")))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("成本汇总:只读查询,不释放过期预留")
  void shouldNotReleaseReservations_whenSummarizingCost() {
    when(mapper.find(eq("tenant-a"), any())).thenReturn(null);

    service.summary("tenant-a", YearMonth.of(2026, 9));

    verify(mapper, never()).releaseStaleReservations(anyString(), any(), any());
  }
}
