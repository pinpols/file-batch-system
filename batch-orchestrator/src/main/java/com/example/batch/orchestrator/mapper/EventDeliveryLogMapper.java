package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.query.EventDeliveryLogQuery;
import java.util.List;

public interface EventDeliveryLogMapper {

    int insert(EventDeliveryLogEntity entity);

    List<EventDeliveryLogEntity> selectByQuery(EventDeliveryLogQuery query);
}
