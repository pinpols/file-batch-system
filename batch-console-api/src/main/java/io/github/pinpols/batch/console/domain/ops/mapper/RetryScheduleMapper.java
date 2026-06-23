package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.entity.RetryScheduleEntity;
import io.github.pinpols.batch.console.domain.ops.query.RetryScheduleQuery;
import java.util.List;

public interface RetryScheduleMapper {

  List<RetryScheduleEntity> selectByQuery(RetryScheduleQuery query);

  long countByQuery(RetryScheduleQuery query);
}
