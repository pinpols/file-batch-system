package com.example.batch.console.domain.ops.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.ops.query.OutboxDeliveryLogQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

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
