package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import com.example.batch.orchestrator.domain.query.EventOutboxRetryQuery;
import java.util.List;

public interface EventOutboxRetryMapper {

  int insert(EventOutboxRetryEntity entity);

  List<EventOutboxRetryEntity> selectByQuery(EventOutboxRetryQuery query);
}
