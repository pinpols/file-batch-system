package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.query.RetryScheduleQuery;
import java.util.List;

public interface RetryScheduleMapper {

    List<RetryScheduleEntity> selectByQuery(RetryScheduleQuery query);
}
