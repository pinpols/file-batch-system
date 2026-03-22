package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.config.OrchestratorWorkerClientProperties;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.common.dto.WorkerHeartbeatDto;
import java.time.Instant;
import com.example.batch.worker.core.support.WorkerRegistryClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.core.env.Environment;

@Component
@RequiredArgsConstructor
public class HttpWorkerRegistryClient implements WorkerRegistryClient {

    private final OrchestratorWorkerClientProperties properties;
    private final RestClient.Builder builder;
    private final Environment environment;
    private RestClient restClient;

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
        client().post()
                .uri("/internal/workers/{workerId}/status", registration.getWorkerId())
                .body(toHeartbeatDto(registration))
                .retrieve()
                .toBodilessEntity();
        return registration;
    }

    private void post(String path, WorkerRegistration registration) {
        client().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toHeartbeatDto(registration))
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient client() {
        RestClient current = this.restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.restClient == null) {
                this.restClient = builder.baseUrl(resolveBaseUrl()).build();
            }
            return this.restClient;
        }
    }

    private String resolveBaseUrl() {
        String configuredBaseUrl = properties.getBaseUrl();
        if (StringUtils.hasText(configuredBaseUrl) && !configuredBaseUrl.contains("${")) {
            return configuredBaseUrl;
        }
        String localPort = environment.getProperty("local.server.port");
        if (StringUtils.hasText(localPort)) {
            return "http://127.0.0.1:" + localPort;
        }
        throw new IllegalStateException("Unable to resolve batch.orchestrator.base-url for worker registry client");
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
