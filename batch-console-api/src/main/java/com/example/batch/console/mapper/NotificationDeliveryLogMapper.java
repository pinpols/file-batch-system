package com.example.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface NotificationDeliveryLogMapper {

    List<Map<String, Object>> selectByTenant(
            @Param("tenantId") String tenantId, @Param("limit") int limit);

    int insert(Map<String, Object> params);

    int updateStatus(Map<String, Object> params);
}
