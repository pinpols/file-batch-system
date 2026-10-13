package io.github.pinpols.batch.trigger.mapper;

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
      Long id, String tenantId, String calendarCode, LocalDate bizDate, Instant cutoffAt) {
    return transitionToCutoff(
        id,
        tenantId,
        calendarCode,
        bizDate,
        cutoffAt,
        DayStatus.OPEN.name(),
        DayStatus.CUTOFF.name());
  }

  int transitionToCutoff(
      @Param("id") Long id,
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("cutoffAt") Instant cutoffAt,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus);
}
