package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.business_calendar CRUD。原 {@code BusinessCalendarRepository}（Spring Data JDBC）已下线， 配置态写读统一由本
 * Mapper 接管。
 */
public interface BusinessCalendarMapper {

  BusinessCalendarRecord selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled);

  List<BusinessCalendarRecord> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  BusinessCalendarRecord selectById(@Param("id") Long id);

  int insert(BusinessCalendarRecord record);

  int update(BusinessCalendarRecord record);

  int deleteById(@Param("id") Long id);
}
