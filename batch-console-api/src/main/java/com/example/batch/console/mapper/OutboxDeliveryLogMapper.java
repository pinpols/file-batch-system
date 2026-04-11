package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.query.OutboxDeliveryLogQuery;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface OutboxDeliveryLogMapper {

    List<Map<String, Object>> selectByQuery(OutboxDeliveryLogQuery query);

    long countByQuery(
            @Param("tenantId") String tenantId,
            @Param("deliveryStatus") String deliveryStatus,
            @Param("eventType") String eventType,
            @Param("eventKey") String eventKey,
            @Param("pageRequest") PageRequest pageRequest);

    long countByStatus(
            @Param("tenantId") String tenantId, @Param("deliveryStatus") String deliveryStatus);
}
