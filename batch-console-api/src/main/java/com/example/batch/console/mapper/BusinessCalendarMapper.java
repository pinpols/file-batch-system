package com.example.batch.console.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface BusinessCalendarMapper {

    Map<String, Object> selectActiveByTenantAndCalendarCode(@Param("tenantId") String tenantId,
                                                            @Param("calendarCode") String calendarCode);
}
