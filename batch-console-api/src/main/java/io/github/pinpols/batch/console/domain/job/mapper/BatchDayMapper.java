package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.query.BatchDayQuery;
import io.github.pinpols.batch.console.domain.job.view.BatchDayJobSummaryView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayScheduledOccurrenceView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayView;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchDayMapper {

  List<BatchDayView> selectByQuery(BatchDayQuery parameters);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("fromBizDate") LocalDate fromBizDate,
      @Param("toBizDate") LocalDate toBizDate);

  BatchDayView selectWindow(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<BatchDayScheduledOccurrenceView> selectScheduledOccurrences(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<BatchDayJobSummaryView> selectJobSummaries(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<String> selectFailedJobCodes(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);
}
