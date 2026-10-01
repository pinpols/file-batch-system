package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiMonthlyUsageEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiMonthlyUsageMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 估算 AI 服务费用，并原子预留租户月度预算。 */
@Service
@RequiredArgsConstructor
public class ConsoleAiCostService {

  private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");
  private static final BigDecimal BYTES_TO_TOKEN_UPPER_BOUND = new BigDecimal("2");
  private static final BigDecimal ZERO_COST = BigDecimal.ZERO.setScale(8, RoundingMode.UP);
  private static final String AI_NOT_CONFIGURED_ERROR = "error.ai.assistant_not_configured";

  private final ConsoleAiMonthlyUsageMapper mapper;
  private final ConsoleAiProperties properties;

  public void validateProviders(List<String> providerNames) {
    if (EmptyChecks.isEmpty(usableRates())) {
      if (properties.getCost().getMonthlyBudgetUsd().signum() > 0) {
        throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, AI_NOT_CONFIGURED_ERROR);
      }
      return;
    }
    if (providerNames.stream().anyMatch(provider -> EmptyChecks.isNull(rateFor(provider)))) {
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, AI_NOT_CONFIGURED_ERROR);
    }
  }

  @Transactional
  public Reservation reserve(String tenantId, String promptPayload, String systemPrompt) {
    return reserve(tenantId, promptPayload, systemPrompt, 0);
  }

  @Transactional
  public Reservation reserve(
      String tenantId, String promptPayload, String systemPrompt, int imageCount) {
    ConsoleAiProperties.Cost config = properties.getCost();
    if (EmptyChecks.isEmpty(usableRates())) {
      if (imageCount > 0 || config.getMonthlyBudgetUsd().signum() > 0) {
        throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, AI_NOT_CONFIGURED_ERROR);
      }
      return Reservation.unpriced();
    }
    BigDecimal cap = maximumCost(promptPayload, systemPrompt, imageCount);
    BigDecimal monthlyBudget = config.getMonthlyBudgetUsd();
    LocalDate billingMonth = YearMonth.now(ZoneOffset.UTC).atDay(1);
    mapper.setTenantContext(tenantId);
    mapper.releaseStaleReservations(
        tenantId, billingMonth, OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
    if (monthlyBudget.signum() > 0) {
      Boolean reserved = mapper.reserve(tenantId, billingMonth, cap, monthlyBudget);
      if (!Boolean.TRUE.equals(reserved)) {
        throw BizException.of(ResultCode.RATE_LIMITED, "error.ai.rate_limited");
      }
      return new Reservation(tenantId, billingMonth, cap, true);
    }
    mapper.reserveUnbounded(tenantId, billingMonth, cap);
    return new Reservation(tenantId, billingMonth, cap, true);
  }

  @Transactional
  public CostResult settle(
      Reservation reservation, String provider, Integer promptTokens, Integer completionTokens) {
    if (EmptyChecks.isNull(reservation) || !reservation.priced()) {
      return new CostResult(null, "UNPRICED");
    }
    boolean hasUsage =
        EmptyChecks.isNotNull(promptTokens) && EmptyChecks.isNotNull(completionTokens);
    BigDecimal amount = hasUsage
        ? calculate(provider, promptTokens, completionTokens)
        : reservation.reservedAmount();
    if (reservation.budgetReserved()) {
      mapper.setTenantContext(reservation.tenantId());
      mapper.settle(
          reservation.tenantId(),
          reservation.billingMonth(),
          reservation.reservedAmount(),
          amount,
          EmptyChecks.isNull(promptTokens) ? 0L : promptTokens,
          EmptyChecks.isNull(completionTokens) ? 0L : completionTokens);
    }
    return new CostResult(amount, hasUsage ? "PRICED" : "RESERVED");
  }

  @Transactional
  public void release(Reservation reservation) {
    if (EmptyChecks.isNotNull(reservation) && reservation.budgetReserved()) {
      mapper.setTenantContext(reservation.tenantId());
      mapper.release(
          reservation.tenantId(), reservation.billingMonth(), reservation.reservedAmount());
    }
  }

  @Transactional
  public CostSummary summary(String tenantId, YearMonth month) {
    mapper.setTenantContext(tenantId);
    LocalDate billingMonth = month.atDay(1);
    ConsoleAiMonthlyUsageEntity usage = mapper.find(tenantId, billingMonth);
    if (EmptyChecks.isNull(usage)) {
      return new CostSummary(
          month, 0, 0, 0, ZERO_COST, ZERO_COST, properties.getCost().getMonthlyBudgetUsd());
    }
    return new CostSummary(
        month,
        usage.getRequestCount(),
        usage.getPromptTokens(),
        usage.getCompletionTokens(),
        usage.getEstimatedCostUsd(),
        usage.getReservedCostUsd(),
        properties.getCost().getMonthlyBudgetUsd());
  }

  private BigDecimal maximumCost(String promptPayload, String systemPrompt, int imageCount) {
    long bytes = bytes(promptPayload) + bytes(systemPrompt);
    BigDecimal estimatedInputTokens = BigDecimal.valueOf(bytes)
        .multiply(BYTES_TO_TOKEN_UPPER_BOUND)
        .add(BigDecimal.valueOf(
            (long) Math.max(0, imageCount) * properties.getImage().getReservationTokensPerImage()))
        .setScale(0, RoundingMode.CEILING);
    BigDecimal maxOutputTokens =
        BigDecimal.valueOf(Math.max(1, properties.getMaxCompletionTokens()));
    BigDecimal maxRateCost = usableRates().stream()
        .map(rate -> rateCost(rate, estimatedInputTokens, maxOutputTokens))
        .max(BigDecimal::compareTo)
        .orElseThrow();
    return maxRateCost
        .multiply(properties.getCost().getReservationMargin())
        .setScale(8, RoundingMode.CEILING);
  }

  private BigDecimal calculate(String provider, int promptTokens, int completionTokens) {
    ConsoleAiProperties.ProviderRate rate = rateFor(provider);
    if (EmptyChecks.isNull(rate)) {
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, AI_NOT_CONFIGURED_ERROR);
    }
    return rateCost(
        rate,
        BigDecimal.valueOf(Math.max(0, promptTokens)),
        BigDecimal.valueOf(Math.max(0, completionTokens)));
  }

  private ConsoleAiProperties.ProviderRate rateFor(String provider) {
    String normalized = normalize(provider);
    for (Map.Entry<String, ConsoleAiProperties.ProviderRate> entry :
        properties.getCost().getProviderRates().entrySet()) {
      if (normalize(entry.getKey()).equals(normalized)) {
        return isUsable(entry.getValue()) ? entry.getValue() : null;
      }
    }
    if (normalized.equals(normalize(properties.getOpenaiCompatible().getProviderName()))) {
      return properties.getCost().getProviderRates().entrySet().stream()
          .filter(entry -> normalize(entry.getKey()).equals("openai-compatible"))
          .map(Map.Entry::getValue)
          .filter(ConsoleAiCostService::isUsable)
          .findFirst()
          .orElse(null);
    }
    return null;
  }

  private List<ConsoleAiProperties.ProviderRate> usableRates() {
    return properties.getCost().getProviderRates().values().stream()
        .filter(Objects::nonNull)
        .filter(ConsoleAiCostService::isUsable)
        .toList();
  }

  private static boolean isUsable(ConsoleAiProperties.ProviderRate rate) {
    return EmptyChecks.isNotNull(rate)
        && EmptyChecks.isNotNull(rate.getInputUsdPerMillionTokens())
        && EmptyChecks.isNotNull(rate.getOutputUsdPerMillionTokens())
        && (rate.getInputUsdPerMillionTokens().signum() > 0
            || rate.getOutputUsdPerMillionTokens().signum() > 0);
  }

  private BigDecimal rateCost(
      ConsoleAiProperties.ProviderRate rate, BigDecimal inputTokens, BigDecimal outputTokens) {
    Objects.requireNonNull(rate);
    return rate.getInputUsdPerMillionTokens()
        .multiply(inputTokens)
        .add(rate.getOutputUsdPerMillionTokens().multiply(outputTokens))
        .divide(TOKENS_PER_MILLION, 8, RoundingMode.HALF_UP);
  }

  private static long bytes(String value) {
    return EmptyChecks.isNull(value) ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String normalize(String value) {
    return EmptyChecks.isNull(value) ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public record Reservation(
      String tenantId, LocalDate billingMonth, BigDecimal reservedAmount, boolean budgetReserved) {
    private static Reservation unpriced() {
      return new Reservation(null, null, null, false);
    }

    private boolean priced() {
      return EmptyChecks.isNotNull(reservedAmount);
    }
  }

  public record CostResult(BigDecimal amount, String status) {}

  public record CostSummary(
      YearMonth month,
      long requestCount,
      long promptTokens,
      long completionTokens,
      BigDecimal estimatedCostUsd,
      BigDecimal reservedCostUsd,
      BigDecimal monthlyBudgetUsd) {}
}
