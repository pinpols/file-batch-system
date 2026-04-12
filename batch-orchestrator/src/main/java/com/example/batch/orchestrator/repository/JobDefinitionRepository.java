package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface JobDefinitionRepository extends CrudRepository<JobDefinitionRecord, Long> {

  List<JobDefinitionRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

  JobDefinitionRecord findFirstByTenantIdAndJobCodeAndEnabled(
      String tenantId, String jobCode, Boolean enabled);
}
