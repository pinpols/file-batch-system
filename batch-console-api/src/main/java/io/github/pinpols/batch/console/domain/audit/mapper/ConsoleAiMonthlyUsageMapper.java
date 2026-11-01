package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiMonthlyUsageSettleParam;
import io.github.pinpols.batch.console.domain.audit.view.ConsoleAiMonthlyUsageView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsoleAiMonthlyUsageMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  Boolean reserve(
      @Param("tenantId") String tenantId,
      @Param("billingMonth") LocalDate billingMonth,
      @Param("amount") BigDecimal amount,
      @Param("budget") BigDecimal budget);

  int reserveUnbounded(
      @Param("tenantId") String tenantId,
      @Param("billingMonth") LocalDate billingMonth,
      @Param("amount") BigDecimal amount);

  int settle(ConsoleAiMonthlyUsageSettleParam parameters);

  int release(
      @Param("tenantId") String tenantId,
      @Param("billingMonth") LocalDate billingMonth,
      @Param("reservedAmount") BigDecimal reservedAmount);

  int releaseStaleReservations(
      @Param("tenantId") String tenantId,
      @Param("billingMonth") LocalDate billingMonth,
      @Param("cutoff") OffsetDateTime cutoff);

  ConsoleAiMonthlyUsageView find(
      @Param("tenantId") String tenantId, @Param("billingMonth") LocalDate billingMonth);
}
