package io.github.pinpols.batch.console.domain.audit.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

@Data
public class ConsoleAiMonthlyUsageView {

  private String tenantId;
  private LocalDate billingMonth;
  private long requestCount;
  private long promptTokens;
  private long completionTokens;
  private BigDecimal estimatedCostUsd;
  private BigDecimal reservedCostUsd;
}
