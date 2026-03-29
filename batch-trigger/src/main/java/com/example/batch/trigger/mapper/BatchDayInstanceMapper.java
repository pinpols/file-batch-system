package com.example.batch.trigger.mapper;

import com.example.batch.trigger.support.BatchDayCutoffCandidate;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchDayInstanceMapper {

    List<BatchDayCutoffCandidate> selectOpenCutoffCandidates();

    int markCutoff(@Param("id") Long id,
                   @Param("tenantId") String tenantId,
                   @Param("calendarCode") String calendarCode,
                   @Param("bizDate") java.time.LocalDate bizDate,
                   @Param("cutoffAt") Instant cutoffAt);
}
