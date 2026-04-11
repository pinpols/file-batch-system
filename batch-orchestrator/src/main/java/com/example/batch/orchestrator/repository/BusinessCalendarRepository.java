package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface BusinessCalendarRepository extends CrudRepository<BusinessCalendarRecord, Long> {

    List<BusinessCalendarRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

    BusinessCalendarRecord findFirstByTenantIdAndCalendarCodeAndEnabled(
            String tenantId, String calendarCode, Boolean enabled);
}
