package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.support.TaskExecutionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class HttpTaskExecutionClient implements TaskExecutionClient {

    private final OrchestratorTaskClientProperties properties;
    private final RestClient.Builder builder;
    private final Environment environment;
    private RestClient restClient;

    @Override
    public boolean claim(String tenantId, Long taskId, String workerId) {
        try {
            client().post()
                    .uri("/internal/tasks/{taskId}/claim", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClaimRequest(tenantId, workerId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT || ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public boolean renewLease(String tenantId, Long taskId, String workerId) {
        try {
            client().post()
                    .uri("/internal/tasks/{taskId}/renew", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClaimRequest(tenantId, workerId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT || ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public void report(TaskExecutionReport report) {
        client().post()
                .uri("/internal/tasks/{taskId}/report", report.getTaskId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(report)
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
        throw new IllegalStateException("Unable to resolve batch.worker.task-client.base-url for task execution client");
    }

    private record ClaimRequest(String tenantId, String workerId) {
    }
}
