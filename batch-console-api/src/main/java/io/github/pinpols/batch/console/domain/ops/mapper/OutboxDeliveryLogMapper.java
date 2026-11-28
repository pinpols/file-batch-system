package io.github.pinpols.batch.console.domain.ops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogCountQuery;
import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogQuery;
import io.github.pinpols.batch.console.domain.ops.view.OutboxDeliveryLogRow;

public interface OutboxDeliveryLogMapper {

    List<OutboxDeliveryLogRow> selectByQuery(OutboxDeliveryLogQuery query);

    long countByQuery(OutboxDeliveryLogCountQuery parameters);

    long countByStatus(@Param("tenantId") String tenantId, @Param("deliveryStatus") String deliveryStatus);
}
