package com.example.batch.sdk.internal;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 平台 {@code /internal/*} 调用封装。路径与 body 字段集对齐 batch-orchestrator 真实 controller:
 *
 * <ul>
 *   <li>{@code WorkerController}:{@code POST /internal/workers/register} / {@code POST
 *       /internal/workers/{workerCode}/heartbeat} / {@code POST
 *       /internal/workers/{workerCode}/deactivate}
 *   <li>{@code TaskController}:{@code POST /internal/tasks/{taskId}/claim} / {@code POST
 *       /internal/tasks/{taskId}/report} / {@code POST /internal/tasks/{taskId}/renew}
 * </ul>
 *
 * <p>用 JDK {@link HttpClient}(不引第三方),JSON 序列化用 jackson。每个调用都带 {@code X-Batch-Api-Key}(P2)+ {@code
 * X-Batch-Tenant-Id} + 写操作的 {@code Idempotency-Key}。
 */
@Slf4j
public class PlatformHttpClient {

  private final BatchPlatformClientConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public PlatformHttpClient(BatchPlatformClientConfig config) {
    this.config = config;
    this.httpClient = HttpClient.newBuilder().connectTimeout(config.getHttpTimeout()).build();
    this.objectMapper =
        new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  }

  /** POST /internal/workers/register — body schema = WorkerHeartbeatDto。 */
  public Map<String, Object> register(Map<String, Object> body) throws IOException {
    return postJson("/internal/workers/register", body, null);
  }

  /** POST /internal/workers/{workerCode}/heartbeat — body schema = WorkerHeartbeatDto。 */
  public Map<String, Object> heartbeat(String workerCode, Map<String, Object> body)
      throws IOException {
    return postJson("/internal/workers/" + workerCode + "/heartbeat", body, null);
  }

  /** POST /internal/workers/{workerCode}/deactivate — SDK stop 时优雅下线。 */
  public Map<String, Object> deactivate(String workerCode, Map<String, Object> body)
      throws IOException {
    return postJson("/internal/workers/" + workerCode + "/deactivate", body, null);
  }

  /** POST /internal/tasks/{taskId}/claim — body=TaskClaimRequest,返回 EffectiveTaskConfig JSON。 */
  public Map<String, Object> claim(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    return postJson("/internal/tasks/" + taskId + "/claim", body, idempotencyKey);
  }

  /** POST /internal/tasks/{taskId}/report — body schema = TaskExecutionReportDto。 */
  public Map<String, Object> report(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    return postJson("/internal/tasks/" + taskId + "/report", body, idempotencyKey);
  }

  /** POST /internal/tasks/{taskId}/renew — body=TaskClaimRequest(同 claim 字段集)。 */
  public Map<String, Object> renew(Long taskId, Map<String, Object> body) throws IOException {
    return postJson("/internal/tasks/" + taskId + "/renew", body, null);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> postJson(String path, Map<String, Object> body, String idempotencyKey)
      throws IOException {
    String url = config.getBaseUrl() + path;
    byte[] payload = objectMapper.writeValueAsBytes(body == null ? Map.of() : body);

    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(config.getHttpTimeout())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Batch-Tenant-Id", config.getTenantId())
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
    if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
      req.header("X-Batch-Api-Key", config.getApiKey());
    }
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      req.header("Idempotency-Key", idempotencyKey);
    }

    HttpResponse<byte[]> resp;
    try {
      resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted: " + url, ie);
    }

    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
      if (resp.body() == null || resp.body().length == 0) {
        return Map.of();
      }
      return objectMapper.readValue(resp.body(), Map.class);
    }
    String errBody = resp.body() == null ? "" : new String(resp.body(), StandardCharsets.UTF_8);
    // 状态码下放给调用方(dispatcher / scheduler)按 401/403/409/5xx 分类处理;
    // 见 PlatformHttpException javadoc。
    throw new PlatformHttpException(
        resp.statusCode(),
        "HTTP " + resp.statusCode() + " from " + url + " body=" + truncate(errBody, 500));
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }
}
