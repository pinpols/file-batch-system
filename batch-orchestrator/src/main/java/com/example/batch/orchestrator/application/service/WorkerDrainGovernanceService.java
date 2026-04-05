package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import java.util.List;

public interface WorkerDrainGovernanceService {

    WorkerRegistryRecord startDrain(String tenantId, String workerCode, Integer timeoutSeconds);

    WorkerRegistryRecord forceOffline(String tenantId, String workerCode);

    WorkerRegistryRecord takeover(String tenantId, String workerCode);

    List<JobTaskEntity> listClaimedTasks(String tenantId, String workerCode);

    void takeoverAfterDrainTimeout(String tenantId, String workerCode);
}
