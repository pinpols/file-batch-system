package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;

import io.github.pinpols.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import io.github.pinpols.batch.orchestrator.domain.query.EventDeliveryLogQuery;

public interface EventDeliveryLogMapper {

    int insert(EventDeliveryLogEntity entity);

    int insertIntoArchive(EventDeliveryLogEntity entity);

    List<EventDeliveryLogEntity> selectByQuery(EventDeliveryLogQuery query);
}
