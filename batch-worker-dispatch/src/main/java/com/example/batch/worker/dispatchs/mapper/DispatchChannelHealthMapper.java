package com.example.batch.worker.dispatchs.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface DispatchChannelHealthMapper {

    List<Map<String, Object>> findEnabledProbeChannels(
            @Param("types") List<String> types, @Param("limit") int limit);

    Map<String, Object> findHealth(
            @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

    int upsertHealth(Map<String, Object> params);
}
