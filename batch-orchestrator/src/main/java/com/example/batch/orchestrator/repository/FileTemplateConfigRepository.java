package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.FileTemplateConfigRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface FileTemplateConfigRepository extends CrudRepository<FileTemplateConfigRecord, Long> {

    List<FileTemplateConfigRecord> findByTenantIdAndTemplateTypeAndEnabled(String tenantId, String templateType, Boolean enabled);
}
