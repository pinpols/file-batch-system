package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import io.github.pinpols.batch.orchestrator.domain.query.EventDeliveryLogQuery;
import java.util.List;

public interface EventDeliveryLogMapper {

  int insert(EventDeliveryLogEntity entity);

  List<EventDeliveryLogEntity> selectByQuery(EventDeliveryLogQuery query);
}
