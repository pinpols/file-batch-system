package com.example.batch.console.service;

import com.example.batch.console.domain.request.DrainWorkerRequest;
import com.example.batch.console.domain.request.ForceOfflineWorkerRequest;
import java.util.List;
import java.util.Map;

public interface ConsoleWorkerApplicationService {

    Map<String, Object> drain(String workerCode, DrainWorkerRequest request, String idempotencyKey);

    Map<String, Object> forceOffline(String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey);

    List<Map<String, Object>> claimedTasks(String tenantId, String workerCode);
}
