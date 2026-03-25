package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.support.TaskExecutionClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls orchestrator internal task APIs with explicit timeouts, bounded retries on transient failures,
 * and Micrometer counters for report failures ({@code worker.report.failed.total} tagged by {@code reason}).
 */
@Component
@Slf4j
public class HttpTaskExecutionClient implements TaskExecutionClient {

    private final OrchestratorTaskClientProperties properties;
    private final RestClient.Builder builder;
    private final Environment environment;
    private final Optional<MeterRegistry> meterRegistry;
    private RestClient restClient;

    public HttpTaskExecutionClient(
            OrchestratorTaskClientProperties properties,
            RestClient.Builder builder,
            Environment environment,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.builder = builder;
        this.environment = environment;
        this.meterRegistry = Optional.ofNullable(meterRegistry);
    }

    @Override
    public boolean claim(String tenantId, Long taskId, String workerId) {
        return executeClaimLike(
                "claim",
                () -> client().post()
                        .uri("/internal/tasks/{taskId}/claim", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ClaimRequest(tenantId, workerId))
                        .retrieve()
                        .toBodilessEntity());
    }

    @Override
    public boolean renewLease(String tenantId, Long taskId, String workerId) {
        return executeClaimLike(
                "renew",
                () -> client().post()
                        .uri("/internal/tasks/{taskId}/renew", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ClaimRequest(tenantId, workerId))
                        .retrieve()
                        .toBodilessEntity());
    }

    @Override
    public void report(TaskExecutionReport report) {
        int max = Math.max(1, properties.getReportMaxAttempts());
        long backoff = Math.max(1L, properties.getReportInitialBackoffMillis());
        long cap = Math.max(backoff, properties.getReportMaxBackoffMillis());
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                client().post()
                        .uri("/internal/tasks/{taskId}/report", report.getTaskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(report)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    recordReportFailure("RATE_LIMITED");
                    logStructuredReportFailure("RATE_LIMITED", attempt, max, ex);
                    throw ex;
                }
                recordReportFailure("CLIENT_ERROR");
                logStructuredReportFailure("CLIENT_ERROR", attempt, max, ex);
                throw ex;
            } catch (HttpServerErrorException ex) {
                recordReportFailure("SERVER_ERROR");
                if (attempt >= max) {
                    logStructuredReportFailure("SERVER_ERROR", attempt, max, ex);
                    throw ex;
                }
                log.warn("orchestrator report transient failure, will retry: attempt={}/{}, status={}, message={}",
                        attempt, max, ex.getStatusCode(), ex.getMessage());
                sleepBackoff(backoff);
                backoff = Math.min(cap, backoff * 2);
            } catch (ResourceAccessException ex) {
                recordReportFailure("IO_TIMEOUT");
                if (attempt >= max) {
                    logStructuredReportFailure("IO_TIMEOUT", attempt, max, ex);
                    throw ex;
                }
                log.warn("orchestrator report I/O failure, will retry: attempt={}/{}, message={}",
                        attempt, max, ex.getMessage());
                sleepBackoff(backoff);
                backoff = Math.min(cap, backoff * 2);
            }
        }
    }

    private boolean executeClaimLike(String operation, Runnable call) {
        int max = Math.max(1, properties.getClaimMaxAttempts());
        long backoff = Math.max(1L, properties.getClaimInitialBackoffMillis());
        long cap = Math.max(backoff, properties.getClaimMaxBackoffMillis());
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                call.run();
                return true;
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode() == HttpStatus.CONFLICT || ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                    return false;
                }
                throw ex;
            } catch (HttpServerErrorException | ResourceAccessException ex) {
                if (attempt >= max) {
                    log.warn("{} failed after {} attempts: {}", operation, max, ex.getMessage());
                    throw ex instanceof RuntimeException r ? r : new IllegalStateException(ex);
                }
                log.warn("{} transient failure, retrying: attempt={}/{}, message={}",
                        operation, attempt, max, ex.getMessage());
                sleepBackoff(backoff);
                backoff = Math.min(cap, backoff * 2);
            }
        }
        throw new IllegalStateException(operation + " exhausted retries without outcome");
    }

    private void recordReportFailure(String reason) {
        meterRegistry.ifPresent(reg -> reg.counter("worker.report.failed.total", "reason", reason).increment());
    }

    private void logStructuredReportFailure(String reasonCode, int attempt, int max, Exception ex) {
        log.warn("orchestrator report aborted: reasonCode={}, attempt={}/{}, error={}",
                reasonCode, attempt, max, ex.toString());
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during orchestrator client backoff", ie);
        }
    }

    private RestClient client() {
        RestClient current = this.restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.restClient == null) {
                JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                                .build());
                factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
                this.restClient = builder
                        .baseUrl(resolveBaseUrl())
                        .requestFactory(factory)
                        .build();
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
