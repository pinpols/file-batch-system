package io.github.pinpols.batch.console.domain.ops.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.ops.entity.RetryScheduleEntity;
import io.github.pinpols.batch.console.domain.ops.query.RetryScheduleQuery;

public interface RetryScheduleMapper {

    List<RetryScheduleEntity> selectByQuery(RetryScheduleQuery query);

    long countByQuery(RetryScheduleQuery query);
}
