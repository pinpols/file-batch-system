package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.config.OrchestratorWorkerClientProperties;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.common.dto.WorkerHeartbeatDto;
import java.time.Instant;
import com.example.batch.worker.core.support.WorkerRegistryClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class HttpWorkerRegistryClient implements WorkerRegistryClient {

    private final OrchestratorWorkerClientProperties properties;
    private final RestClient.Builder builder;
    private RestClient restClient;

    @PostConstruct
    void initialize() {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public WorkerRegistration register(WorkerRegistration registration) {
        post("/internal/workers/register", registration);
        return registration;
    }

    @Override
    public WorkerRegistration heartbeat(WorkerRegistration registration) {
        post("/internal/workers/" + registration.getWorkerId() + "/heartbeat", registration);
        return registration;
    }

    @Override
    public void deactivate(WorkerRegistration registration) {
        registration.setStatus("OFFLINE");
        updateStatus(registration);
    }

    @Override
    public WorkerRegistration updateStatus(WorkerRegistration registration) {
        restClient.post()
                .uri("/internal/workers/{workerId}/status", registration.getWorkerId())
                .body(toHeartbeatDto(registration))
                .retrieve()
                .toBodilessEntity();
        return registration;
    }

    private void post(String path, WorkerRegistration registration) {
        restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toHeartbeatDto(registration))
                .retrieve()
                .toBodilessEntity();
    }

    private WorkerHeartbeatDto toHeartbeatDto(WorkerRegistration registration) {
        return new WorkerHeartbeatDto(
                registration.getTenantId(),
                registration.getWorkerId(),
                registration.getWorkerGroup(),
                registration.getStatus(),
                registration.getHost(),
                null,
                null,
                registration.getLastHeartbeatAt() == null ? Instant.now() : registration.getLastHeartbeatAt().toInstant(),
                null,
                registration.getCurrentLoad()
        );
    }
}
