package io.github.pinpols.batch.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.dispatcher.HeartbeatDirective;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(config.getHttpTimeout()).build();
    this.objectMapper = SdkJsonMapperFactory.create();
  }

  /** POST /internal/workers/register — body schema = WorkerHeartbeatDto。 */
  public WorkerRegistrationResponse register(Map<String, Object> body) throws IOException {
    return postJson("/internal/workers/register", body, null, WorkerRegistrationResponse.class);
  }

  /** POST /internal/workers/{workerCode}/heartbeat — body schema = WorkerHeartbeatDto。 */
  public HeartbeatDirective heartbeat(String workerCode, Map<String, Object> body)
      throws IOException {
    return postJson(
        "/internal/workers/" + workerCode + "/heartbeat", body, null, HeartbeatDirective.class);
  }

  /** POST /internal/workers/{workerCode}/deactivate — SDK stop 时优雅下线。 */
  public void deactivate(String workerCode, Map<String, Object> body) throws IOException {
    postJson("/internal/workers/" + workerCode + "/deactivate", body, null, Void.class);
  }

  /** POST /internal/tasks/{taskId}/claim — body=TaskClaimRequest,返回 EffectiveTaskConfig JSON。 */
  public TaskClaimResponse claim(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    return postJson(
        "/internal/tasks/" + taskId + "/claim", body, idempotencyKey, TaskClaimResponse.class);
  }

  /** POST /internal/tasks/{taskId}/report — body schema = TaskExecutionReportDto。 */
  public void report(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    postJson("/internal/tasks/" + taskId + "/report", body, idempotencyKey, Void.class);
  }

  /** POST /internal/tasks/{taskId}/renew — body=TaskClaimRequest(同 claim 字段集)。 */
  public TaskRenewResponse renew(Long taskId, Map<String, Object> body) throws IOException {
    return postJson("/internal/tasks/" + taskId + "/renew", body, null, TaskRenewResponse.class);
  }

  private <T> T postJson(
      String path, Map<String, Object> body, String idempotencyKey, Class<T> responseType)
      throws IOException {
    String url = config.getBaseUrl() + path;
    byte[] payload = objectMapper.writeValueAsBytes(body == null ? Map.of() : body);

    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
        .timeout(config.getHttpTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("X-Batch-Tenant-Id", config.getTenantId())
        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
    if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
      req.header("X-Batch-Api-Key", config.getApiKey());
      // 请求签名(方案 A,opt-in):HMAC + 时间戳 + nonce 防重放;须服务端 batch.request-signing.enabled 配合。
      if (config.isRequestSigningEnabled()) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String signature =
            RequestSigner.sign(config.getApiKey(), "POST", path, timestamp, nonce, payload);
        req.header("X-Batch-Timestamp", timestamp)
            .header("X-Batch-Nonce", nonce)
            .header("X-Batch-Signature", signature);
      }
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
      if (responseType == Void.class || EmptyChecks.isEmpty(resp.body())) {
        return null;
      }
      return objectMapper.readValue(resp.body(), responseType);
    }
    // errBody 不进 exception message — 避免错误链一路打 INFO/WARN 时把平台错误 payload 写满日志,
    // 也防止 token / 敏感字段泄露。完整 body 仅 DEBUG 级输出,排障开 DEBUG 看。见 #SDK-P1-3。
    if (log.isDebugEnabled() && resp.body() != null && resp.body().length > 0) {
      String errBody = new String(resp.body(), StandardCharsets.UTF_8);
      log.debug(
          "non-2xx response: status={} url={} body={}",
          resp.statusCode(),
          url,
          truncate(errBody, 500));
    }
    throw new PlatformHttpException(
        resp.statusCode(), "HTTP " + resp.statusCode() + " from " + url);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }

  /** register 回包中 SDK 实际消费和记录的稳定字段；平台新增字段由 Jackson 向后兼容忽略。 */
  public record WorkerRegistrationResponse(
      Long id, String tenantId, String workerCode, String status) {}

  /** claim 回包中 SDK 执行栅栏需要的稳定字段。 */
  public record TaskClaimResponse(String partitionInvocationId) {}

  /** renew 回包中的取消指令。 */
  public record TaskRenewResponse(boolean cancelRequested) {}
}
