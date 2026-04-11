package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.FileTemplateConfigRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface FileTemplateConfigRepository
        extends CrudRepository<FileTemplateConfigRecord, Long> {

    List<FileTemplateConfigRecord> findByTenantIdAndTemplateTypeAndEnabled(
            String tenantId, String templateType, Boolean enabled);
}
