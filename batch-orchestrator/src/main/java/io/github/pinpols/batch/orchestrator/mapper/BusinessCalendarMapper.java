package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.business_calendar 只读 Mapper。CLAUDE.md §持久化"同一表禁双主入口":本表写入主入口在 {@code
 * batch-console-api},orch 端仅 SELECT。
 */
public interface BusinessCalendarMapper {

  BusinessCalendarEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled);

  List<BusinessCalendarEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  List<BusinessCalendarEntity> selectByEnabled(@Param("enabled") Boolean enabled);

  BusinessCalendarEntity selectById(@Param("id") Long id);
}
