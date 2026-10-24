package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.domain.param.BatchDayInstanceMarkCutoffParam;
import io.github.pinpols.batch.trigger.domain.param.BatchDayInstanceTransitionToCutoffParam;


import io.github.pinpols.batch.trigger.support.BatchDayCutoffCandidate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchDayInstanceMapper {

  enum DayStatus {
    OPEN,
    CUTOFF
  }

  default List<BatchDayCutoffCandidate> selectOpenCutoffCandidates() {
    return selectCutoffCandidatesByStatus(DayStatus.OPEN.name());
  }

  List<BatchDayCutoffCandidate> selectCutoffCandidatesByStatus(
      @Param("dayStatus") String dayStatus);

  default int markCutoff(
      BatchDayInstanceMarkCutoffParam parameters) {
    Long id = parameters.id();
    String tenantId = parameters.tenantId();
    String calendarCode = parameters.calendarCode();
    LocalDate bizDate = parameters.bizDate();
    Instant cutoffAt = parameters.cutoffAt();

    BatchDayInstanceTransitionToCutoffParam batchDayInstanceTransitionToCutoffParam = BatchDayInstanceTransitionToCutoffParam.builder()
        .id(id)
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .cutoffAt(cutoffAt)
        .expectedStatus(DayStatus.OPEN.name())
        .targetStatus(DayStatus.CUTOFF.name())
        .build();
    return transitionToCutoff(
        batchDayInstanceTransitionToCutoffParam);
  }

  int transitionToCutoff(
      BatchDayInstanceTransitionToCutoffParam parameters);
}
