package com.example.batch.console.application;

import com.example.batch.console.web.request.DrainWorkerRequest;
import com.example.batch.console.web.request.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import java.util.List;

public interface ConsoleWorkerApplicationService {

    ConsoleWorkerRegistryResponse drain(String workerCode, DrainWorkerRequest request, String idempotencyKey);

    ConsoleWorkerRegistryResponse forceOffline(String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey);

    List<ConsoleWorkerClaimedTaskResponse> claimedTasks(String tenantId, String workerCode);
}
