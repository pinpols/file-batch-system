package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.config.OrchestratorWorkerClientProperties;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerRegistryClient;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.Texts;
import org.springframework.web.client.RestClient;

/**
 * 基于 HTTP 的 Worker 注册中心客户端，向 Orchestrator 内部接口发送注册、心跳、状态更新和下线请求。
 * 延迟初始化 {@link RestClient}（双重检查锁），并在测试环境下通过 {@code local.server.port} 自动
 * 解析 base-url，无需在集成测试中额外配置。
 */
@Component
@RequiredArgsConstructor
public class HttpWorkerRegistryClient implements WorkerRegistryClient {

  private final OrchestratorWorkerClientProperties properties;
  private final BatchSecurityProperties securityProperties;
  private final RestClient.Builder builder;
  private final Environment environment;
  // T-1：DCL 必须 volatile，否则另一线程可能读到未完全构造的 RestClient 对象引用
  private volatile RestClient restClient;

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
    registration.setStatus(WorkerRegistryStatus.OFFLINE.name());
    updateStatus(registration);
  }

  @Override
  public WorkerRegistration updateStatus(WorkerRegistration registration) {
    client()
        .post()
        .uri("/internal/workers/{workerId}/status", registration.getWorkerId())
        .body(toHeartbeatDto(registration))
        .retrieve()
        .toBodilessEntity();
    return registration;
  }

  private void post(String path, WorkerRegistration registration) {
    client()
        .post()
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
        this.restClient =
            builder
                .baseUrl(resolveBaseUrl())
                .defaultHeader("X-Internal-Secret", securityProperties.getInternalSecret())
                .build();
      }
      return this.restClient;
    }
  }

  private String resolveBaseUrl() {
    String configuredBaseUrl = properties.getBaseUrl();
    if (Texts.hasText(configuredBaseUrl) && !configuredBaseUrl.contains("${")) {
      return configuredBaseUrl;
    }
    String localPort = environment.getProperty("local.server.port");
    if (Texts.hasText(localPort)) {
      return "http://127.0.0.1:" + localPort;
    }
    throw new IllegalStateException("batch.orchestrator.base-url is required but not configured");
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
        registration.getLastHeartbeatAt() == null
            ? Instant.now()
            : registration.getLastHeartbeatAt().toInstant(),
        registration.getCapabilityTags(),
        registration.getCurrentLoad());
  }
}
