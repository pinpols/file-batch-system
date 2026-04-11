package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;

import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface BatchDayInstanceRepository extends CrudRepository<BatchDayInstanceRecord, Long> {

    BatchDayInstanceRecord findFirstByTenantIdAndCalendarCodeAndBizDate(
            String tenantId, String calendarCode, LocalDate bizDate);

    List<BatchDayInstanceRecord> findByDayStatusIn(Collection<String> dayStatuses);

    List<BatchDayInstanceRecord> findByDayStatusInAndCutoffAtLessThanEqual(
            Collection<String> dayStatuses, Instant cutoffAt);

    List<BatchDayInstanceRecord> findByDayStatusInAndCutoffAtIsNull(Collection<String> dayStatuses);
}
