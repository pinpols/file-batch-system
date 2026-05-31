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
 * 平台 {@code /api/internal/*} 调用封装(register / heartbeat / claim / report)。
 *
 * <p>用 JDK {@link HttpClient}(不引第三方),JSON 序列化用 jackson。
 *
 * <p>每个 HTTP 调用都带:
 *
 * <ul>
 *   <li>{@code X-Batch-Api-Key} header(P2 启用)
 *   <li>{@code X-Batch-Tenant-Id} header
 *   <li>{@code Idempotency-Key} header(claim / report 等写操作)
 *   <li>{@code Content-Type: application/json}
 * </ul>
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

  /** POST register — 注册 worker,返回 server 端确认。 */
  public Map<String, Object> register(Map<String, Object> body) throws IOException {
    return postJson("/api/internal/workers/register", body, null);
  }

  /** POST heartbeat — 上报心跳。 */
  public Map<String, Object> heartbeat(Map<String, Object> body) throws IOException {
    return postJson("/api/internal/workers/heartbeat", body, null);
  }

  /** POST claim — 抢任务,返回 effective task config 或 4xx(已被别 worker 拿走)。 */
  public Map<String, Object> claim(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    return postJson("/api/internal/tasks/" + taskId + "/claim", body, idempotencyKey);
  }

  /** POST report — 上报结果(success / fail + output)。 */
  public Map<String, Object> report(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    return postJson("/api/internal/tasks/" + taskId + "/report", body, idempotencyKey);
  }

  /** POST renew-lease — 续约。 */
  public Map<String, Object> renewLease(Long taskId, Map<String, Object> body) throws IOException {
    return postJson("/api/internal/tasks/" + taskId + "/renew-lease", body, null);
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
    throw new IOException(
        "HTTP " + resp.statusCode() + " from " + url + " body=" + truncate(errBody, 500));
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }
}
