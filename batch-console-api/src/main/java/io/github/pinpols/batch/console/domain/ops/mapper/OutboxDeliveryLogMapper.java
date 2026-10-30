package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogCountQuery;
import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogQuery;
import io.github.pinpols.batch.console.domain.ops.view.OutboxDeliveryLogRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OutboxDeliveryLogMapper {

  List<OutboxDeliveryLogRow> selectByQuery(OutboxDeliveryLogQuery query);

  long countByQuery(OutboxDeliveryLogCountQuery parameters);

  long countByStatus(
      @Param("tenantId") String tenantId, @Param("deliveryStatus") String deliveryStatus);
}
