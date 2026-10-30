package io.github.pinpols.batch.console.domain.audit.param;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次settle操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleAiMonthlyUsageSettleParam(
    String tenantId,
    LocalDate billingMonth,
    BigDecimal reservedAmount,
    BigDecimal actualAmount,
    long promptTokens,
    long completionTokens) {

  @Override
  public String toString() {
    // 持久化输入可能包含凭据或业务载荷，诊断信息不展开字段值。
    return "ConsoleAiMonthlyUsageSettleParam[redacted]";
  }
}
