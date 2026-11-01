package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiMonthlyUsageMapper;
import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiMonthlyUsageSettleParam;
import io.github.pinpols.batch.console.domain.audit.view.ConsoleAiMonthlyUsageView;
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
    boolean hasProviderWithoutUsableRate =
        providerNames.stream().anyMatch(provider -> EmptyChecks.isNull(rateFor(provider)));
    if (hasProviderWithoutUsableRate) {
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
    OffsetDateTime reservationCheckTime = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime staleReservationCutoff = reservationCheckTime.minusHours(2);
    mapper.releaseStaleReservations(tenantId, billingMonth, staleReservationCutoff);
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
  public CostResult settleMeasured(Reservation reservation, MeasuredUsage usage) {
    Objects.requireNonNull(usage, "usage");
    boolean reservationIsMissingOrUnpriced =
        EmptyChecks.isNull(reservation) || !reservation.priced();
    if (reservationIsMissingOrUnpriced) {
      return new CostResult(null, "UNPRICED");
    }
    BigDecimal amount = calculate(usage.provider(), usage.promptTokens(), usage.completionTokens());
    return settlePricedReservation(
        reservation, amount, usage.promptTokens(), usage.completionTokens(), "PRICED");
  }

  /** 模型调用未返回可靠用量时，按已预留金额保守结算。 */
  @Transactional
  public CostResult settleReserved(Reservation reservation) {
    boolean reservationIsMissingOrUnpriced =
        EmptyChecks.isNull(reservation) || !reservation.priced();
    if (reservationIsMissingOrUnpriced) {
      return new CostResult(null, "UNPRICED");
    }
    return settlePricedReservation(reservation, reservation.reservedAmount(), 0L, 0L, "RESERVED");
  }

  private CostResult settlePricedReservation(
      Reservation reservation,
      BigDecimal amount,
      long promptTokens,
      long completionTokens,
      String status) {
    if (reservation.budgetReserved()) {
      mapper.setTenantContext(reservation.tenantId());
      ConsoleAiMonthlyUsageSettleParam consoleAiMonthlyUsageSettleParam =
          ConsoleAiMonthlyUsageSettleParam.builder()
              .tenantId(reservation.tenantId())
              .billingMonth(reservation.billingMonth())
              .reservedAmount(reservation.reservedAmount())
              .actualAmount(amount)
              .promptTokens(promptTokens)
              .completionTokens(completionTokens)
              .build();
      mapper.settle(consoleAiMonthlyUsageSettleParam);
    }
    return new CostResult(amount, status);
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
    ConsoleAiMonthlyUsageView usage = mapper.find(tenantId, billingMonth);
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
    BigDecimal payloadTokenUpperBound =
        BigDecimal.valueOf(bytes).multiply(BYTES_TO_TOKEN_UPPER_BOUND);
    int nonNegativeImageCount = Math.max(0, imageCount);
    int reservationTokensPerImage = properties.getImage().getReservationTokensPerImage();
    long imageTokenReservation = (long) nonNegativeImageCount * reservationTokensPerImage;
    BigDecimal imageTokenUpperBound = BigDecimal.valueOf(imageTokenReservation);
    BigDecimal estimatedInputTokens =
        payloadTokenUpperBound.add(imageTokenUpperBound).setScale(0, RoundingMode.CEILING);
    int configuredMaxCompletionTokens = properties.getMaxCompletionTokens();
    int positiveMaxCompletionTokens = Math.max(1, configuredMaxCompletionTokens);
    BigDecimal maxOutputTokens = BigDecimal.valueOf(positiveMaxCompletionTokens);
    BigDecimal maxRateCost = usableRates().stream()
        .map(rate -> rateCost(rate, estimatedInputTokens, maxOutputTokens))
        .max(BigDecimal::compareTo)
        .orElseThrow();
    BigDecimal reservationMargin = properties.getCost().getReservationMargin();
    BigDecimal unroundedReservationCost = maxRateCost.multiply(reservationMargin);
    return unroundedReservationCost.setScale(8, RoundingMode.CEILING);
  }

  private BigDecimal calculate(String provider, int promptTokens, int completionTokens) {
    ConsoleAiProperties.ProviderRate rate = rateFor(provider);
    if (EmptyChecks.isNull(rate)) {
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, AI_NOT_CONFIGURED_ERROR);
    }
    int nonNegativePromptTokens = Math.max(0, promptTokens);
    BigDecimal promptTokensForBilling = BigDecimal.valueOf(nonNegativePromptTokens);
    int nonNegativeCompletionTokens = Math.max(0, completionTokens);
    BigDecimal completionTokensForBilling = BigDecimal.valueOf(nonNegativeCompletionTokens);
    return rateCost(rate, promptTokensForBilling, completionTokensForBilling);
  }

  private ConsoleAiProperties.ProviderRate rateFor(String provider) {
    String normalized = normalize(provider);
    for (Map.Entry<String, ConsoleAiProperties.ProviderRate> entry :
        properties.getCost().getProviderRates().entrySet()) {
      if (normalize(entry.getKey()).equals(normalized)) {
        return isUsable(entry.getValue()) ? entry.getValue() : null;
      }
    }
    String compatibleProviderName = properties.getOpenaiCompatible().getProviderName();
    String normalizedCompatibleProviderName = normalize(compatibleProviderName);
    if (normalized.equals(normalizedCompatibleProviderName)) {
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
    BigDecimal inputCost = rate.getInputUsdPerMillionTokens().multiply(inputTokens);
    BigDecimal outputCost = rate.getOutputUsdPerMillionTokens().multiply(outputTokens);
    BigDecimal combinedCost = inputCost.add(outputCost);
    return combinedCost.divide(TOKENS_PER_MILLION, 8, RoundingMode.HALF_UP);
  }

  private static long bytes(String value) {
    return EmptyChecks.isNull(value) ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String normalize(String value) {
    if (EmptyChecks.isNull(value)) {
      return "";
    }
    String trimmedValue = value.trim();
    return trimmedValue.toLowerCase(Locale.ROOT);
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

  /** 模型实际返回的完整用量；缺少任一字段时调用方必须选择 {@link #settleReserved(Reservation)}。 */
  public record MeasuredUsage(String provider, int promptTokens, int completionTokens) {
    public MeasuredUsage {
      Objects.requireNonNull(provider, "provider");
      if (EmptyChecks.isBlank(provider)) {
        throw new IllegalArgumentException("provider must not be blank");
      }
      if (promptTokens < 0 || completionTokens < 0) {
        throw new IllegalArgumentException("token counts must not be negative");
      }
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
