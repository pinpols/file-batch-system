package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.RetryScheduleEntity;
import com.example.batch.orchestrator.domain.query.RetryScheduleQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RetryScheduleMapper {

  int insert(RetryScheduleEntity entity);

  RetryScheduleEntity selectById(@Param("id") Long id);

  List<RetryScheduleEntity> selectByQuery(RetryScheduleQuery query);

  int markRunning(
      @Param("id") Long id,
      @Param("fromStatus") String fromStatus,
      @Param("runningStatus") String runningStatus);

  int markSuccess(@Param("id") Long id, @Param("successStatus") String successStatus);

  int markFailed(
      @Param("id") Long id,
      @Param("retryStatus") String retryStatus,
      @Param("lastErrorCode") String lastErrorCode,
      @Param("lastErrorMessage") String lastErrorMessage,
      @Param("nextRetryAt") Instant nextRetryAt);

  int resetToWaiting(@Param("id") Long id, @Param("waitingStatus") String waitingStatus);
}
