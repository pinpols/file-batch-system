package com.example.batch.console.mapper;

import com.example.batch.console.domain.query.OutboxDeliveryLogQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface OutboxDeliveryLogMapper {

    List<Map<String, Object>> selectByQuery(OutboxDeliveryLogQuery query);

    long countByStatus(@Param("tenantId") String tenantId, @Param("deliveryStatus") String deliveryStatus);
}
