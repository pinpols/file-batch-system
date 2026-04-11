package com.example.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface SubscriptionRuleMapper {

    List<Map<String, Object>> selectByTenant(@Param("tenantId") String tenantId);

    List<Map<String, Object>> selectEnabledByEventType(
            @Param("tenantId") String tenantId, @Param("eventType") String eventType);

    Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    int insert(Map<String, Object> params);

    int update(Map<String, Object> params);

    int deleteById(@Param("tenantId") String tenantId, @Param("id") Long id);
}
