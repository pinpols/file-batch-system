package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface WorkerRegistryRepository extends CrudRepository<WorkerRegistryRecord, Long> {

  List<WorkerRegistryRecord> findByTenantIdAndWorkerGroupAndStatus(
      String tenantId, String workerGroup, String status);

  List<WorkerRegistryRecord> findByTenantIdAndStatus(String tenantId, String status);

  long countByTenantIdAndWorkerGroupAndStatus(String tenantId, String workerGroup, String status);

  long countByTenantIdAndStatus(String tenantId, String status);

  WorkerRegistryRecord findFirstByTenantIdAndWorkerCode(String tenantId, String workerCode);

  List<WorkerRegistryRecord> findByStatus(String status);
}
