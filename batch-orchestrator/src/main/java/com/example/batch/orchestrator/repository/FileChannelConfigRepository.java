package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.FileChannelConfigRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface FileChannelConfigRepository extends CrudRepository<FileChannelConfigRecord, Long> {

    List<FileChannelConfigRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);
}
