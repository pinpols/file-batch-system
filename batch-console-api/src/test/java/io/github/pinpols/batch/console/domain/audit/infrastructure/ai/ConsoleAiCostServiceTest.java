package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
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
  void shouldReserveMonthlyBudgetBeforeProviderCall() {
    when(mapper.reserve(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00"))))
        .thenReturn(true);

    ConsoleAiCostService.Reservation reservation = service.reserve("tenant-a", "hello", "system");

    assertThat(reservation.budgetReserved()).isTrue();
    assertThat(reservation.reservedAmount()).isPositive();
    verify(mapper).setTenantContext("tenant-a");
    verify(mapper)
        .releaseStaleReservations(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.any(LocalDate.class),
            org.mockito.ArgumentMatchers.any(OffsetDateTime.class));
  }

  @Test
  void shouldRejectWhenAtomicMonthlyBudgetReservationFails() {
    when(mapper.reserve(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00"))))
        .thenReturn(false);

    assertThatThrownBy(() -> service.reserve("tenant-a", "hello", "system"))
        .isInstanceOf(BizException.class);
  }

  @Test
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
  void shouldConservativelySettleReservationWhenUsageIsMissing() {
    ConsoleAiCostService.Reservation reservation = new ConsoleAiCostService.Reservation(
        "tenant-a", YearMonth.now(ZoneOffset.UTC).atDay(1), new BigDecimal("0.01"), true);

    ConsoleAiCostService.CostResult result = service.settle(reservation, null, null, null);

    assertThat(result.status()).isEqualTo("RESERVED");
    assertThat(result.amount()).isEqualByComparingTo("0.01");
  }

  @Test
  void shouldRequireRatesForEveryConfiguredProvider() {
    assertThatThrownBy(() -> service.validateProviders(List.of("test-provider", "unpriced")))
        .isInstanceOf(BizException.class);
  }

  @Test
  void costSummaryMustNotMutateReservations() {
    when(mapper.find(
            org.mockito.ArgumentMatchers.eq("tenant-a"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(null);

    service.summary("tenant-a", YearMonth.of(2026, 9));

    verify(mapper, never())
        .releaseStaleReservations(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }
}
