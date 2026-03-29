package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface BatchDayInstanceRepository extends CrudRepository<BatchDayInstanceRecord, Long> {

    BatchDayInstanceRecord findFirstByTenantIdAndCalendarCodeAndBizDate(
            String tenantId,
            String calendarCode,
            LocalDate bizDate
    );

    List<BatchDayInstanceRecord> findByDayStatusIn(Collection<String> dayStatuses);
}
