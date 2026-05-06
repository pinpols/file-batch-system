package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.DisasterDayOverrideEntity;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;

/** ADR-023 disaster_day_override MyBatis 映射。 */
public interface DisasterDayOverrideMapper {

  int insert(DisasterDayOverrideEntity record);

  /**
   * 查 (tenant, calendar, biz_date) 当前 active override；effective_at ≤ now ≤ ttl_until 才算激活。 同
   * (tenant, calendar, biz_date) 在 ttl 期内由 partial unique index 保证至多 1 条。
   */
  DisasterDayOverrideEntity selectActiveByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("now") Instant now);
}
