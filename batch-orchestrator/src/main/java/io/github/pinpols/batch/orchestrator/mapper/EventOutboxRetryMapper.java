package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import io.github.pinpols.batch.orchestrator.domain.query.EventOutboxRetryQuery;
import java.util.List;

public interface EventOutboxRetryMapper {

  int insert(EventOutboxRetryEntity entity);

  List<EventOutboxRetryEntity> selectByQuery(EventOutboxRetryQuery query);
}
