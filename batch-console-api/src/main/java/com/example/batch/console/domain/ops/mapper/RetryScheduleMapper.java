package com.example.batch.console.domain.ops.mapper;

import com.example.batch.console.domain.ops.entity.RetryScheduleEntity;
import com.example.batch.console.domain.ops.query.RetryScheduleQuery;
import java.util.List;

public interface RetryScheduleMapper {

  List<RetryScheduleEntity> selectByQuery(RetryScheduleQuery query);

  long countByQuery(RetryScheduleQuery query);
}
