package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchDayOperationAuditEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 批量日治理操作审计表（V105 batch.batch_day_operation_audit）的 MyBatis mapper。 */
public interface BatchDayOperationAuditMapper {

  int insert(BatchDayOperationAuditEntity entity);

  /** Console 操作历史按 (tenant, calendar, bizDate) 倒序查询。 */
  List<BatchDayOperationAuditEntity> selectByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("limit") int limit);
}
