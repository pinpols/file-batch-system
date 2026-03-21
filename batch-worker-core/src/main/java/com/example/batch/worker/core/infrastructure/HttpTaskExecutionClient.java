package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.support.TaskExecutionClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class HttpTaskExecutionClient implements TaskExecutionClient {

    private final RestClient restClient;

    public HttpTaskExecutionClient(OrchestratorTaskClientProperties properties, RestClient.Builder builder) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public boolean claim(String tenantId, Long taskId, String workerId) {
        try {
            restClient.post()
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
            restClient.post()
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
        restClient.post()
                .uri("/internal/tasks/{taskId}/report", report.getTaskId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(report)
                .retrieve()
                .toBodilessEntity();
    }

    private record ClaimRequest(String tenantId, String workerId) {
    }
}
