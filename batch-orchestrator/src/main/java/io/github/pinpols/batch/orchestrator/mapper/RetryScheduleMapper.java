package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.domain.query.RetryScheduleQuery;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
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

  int markFailed(@Param("p") MarkFailedParam p);

  int resetToWaiting(@Param("id") Long id, @Param("waitingStatus") String waitingStatus);

  @Builder
  record MarkFailedParam(
      Long id,
      String retryStatus,
      String lastErrorCode,
      String lastErrorMessage,
      String lastErrorKey,
      String lastErrorArgs,
      Instant nextRetryAt) {}
}
