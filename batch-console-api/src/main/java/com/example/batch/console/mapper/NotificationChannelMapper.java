package com.example.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface NotificationChannelMapper {

    List<Map<String, Object>> selectByTenant(@Param("tenantId") String tenantId);

    Map<String, Object> selectByCode(
            @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

    int insert(Map<String, Object> params);

    int update(Map<String, Object> params);

    int deleteByCode(@Param("tenantId") String tenantId, @Param("channelCode") String channelCode);
}
