package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.business_calendar CRUD。原 {@code BusinessCalendarRepository}（Spring Data JDBC）已下线， 配置态写读统一由本
 * Mapper 接管。
 */
public interface BusinessCalendarMapper {

  BusinessCalendarEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled);

  List<BusinessCalendarEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  BusinessCalendarEntity selectById(@Param("id") Long id);

  int insert(BusinessCalendarEntity record);

  int update(BusinessCalendarEntity record);

  int deleteById(@Param("id") Long id);
}
