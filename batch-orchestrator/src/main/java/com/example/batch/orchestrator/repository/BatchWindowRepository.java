package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.BatchWindowRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface BatchWindowRepository extends CrudRepository<BatchWindowRecord, Long> {

    List<BatchWindowRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

    Optional<BatchWindowRecord> findFirstByTenantIdAndWindowCodeAndEnabled(
            String tenantId, String windowCode, Boolean enabled);
}
