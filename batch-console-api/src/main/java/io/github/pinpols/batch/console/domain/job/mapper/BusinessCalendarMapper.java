package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface BusinessCalendarMapper {

  Map<String, Object> selectActiveByTenantAndCalendarCode(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int upsertBusinessCalendar(BusinessCalendarUpsertParam param);

  int insert(Map<String, Object> params);

  int update(Map<String, Object> params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}
