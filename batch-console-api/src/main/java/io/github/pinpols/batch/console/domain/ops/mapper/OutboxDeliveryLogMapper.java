package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogQuery;
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
      @Param("traceId") String traceId,
      @Param("pageRequest") PageRequest pageRequest);

  long countByStatus(
      @Param("tenantId") String tenantId, @Param("deliveryStatus") String deliveryStatus);
}
