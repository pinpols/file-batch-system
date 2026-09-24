package io.github.pinpols.batch.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.dispatcher.HeartbeatDirective;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
 * <p>底层使用 OkHttp 5 的 Happy Eyeballs，JSON 序列化用 jackson。每个调用都带 {@code X-Batch-Api-Key}(P2)+ {@code
 * X-Batch-Tenant-Id} + 写操作的 {@code Idempotency-Key}。
 */
@Slf4j
public class PlatformHttpClient {

  private final BatchPlatformClientConfig config;
  private static final MediaType JSON = MediaType.get("application/json");

  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public PlatformHttpClient(BatchPlatformClientConfig config) {
    this(config, createHttpClient(config.getHttpTimeout()));
  }

  PlatformHttpClient(BatchPlatformClientConfig config, OkHttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
    this.objectMapper = SdkJsonMapperFactory.create();
  }

  static OkHttpClient createHttpClient(Duration timeout) {
    return new OkHttpClient.Builder()
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .callTimeout(timeout)
        .fastFallback(true)
        .followRedirects(false)
        .followSslRedirects(false)
        // CLAIM/REPORT 重试由 SDK 决策层持有，连接层不能隐式重放 POST。
        .retryOnConnectionFailure(false)
        .build();
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

  /**
   * 在剩余停机预算内完成优雅下线。该超时只收窄当前调用，不改变共享客户端的正常请求超时。
   */
  public void deactivate(String workerCode, Map<String, Object> body, Duration remainingTimeout)
      throws IOException {
    postJson(
        "/internal/workers/" + workerCode + "/deactivate",
        body,
        null,
        Void.class,
        remainingTimeout);
  }

  /**
   * 取消当前正在解析、建连或等待响应的同步/异步调用。
   *
   * <p>OkHttp 的同步 {@code execute()} 不以线程中断作为稳定取消契约。SDK 停机必须显式取消心跳、续租和超时后的
   * REPORT，才能守住 {@code stop(timeout)} 总预算。
   */
  public void cancelInFlightCalls() {
    httpClient.dispatcher().cancelAll();
  }

  /** 释放空闲连接；不关闭共享执行器，允许同一个 SDK client 在 stop 后再次 start。 */
  public void evictIdleConnections() {
    httpClient.connectionPool().evictAll();
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
    return postJson(path, body, idempotencyKey, responseType, null);
  }

  private <T> T postJson(
      String path,
      Map<String, Object> body,
      String idempotencyKey,
      Class<T> responseType,
      Duration timeoutOverride)
      throws IOException {
    String url = config.getBaseUrl() + path;
    byte[] payload = objectMapper.writeValueAsBytes(body == null ? Map.of() : body);

    Request.Builder req = new Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("X-Batch-Tenant-Id", config.getTenantId())
        .post(RequestBody.create(payload, JSON));
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

    okhttp3.Call call = httpClient.newCall(req.build());
    if (EmptyChecks.isNotNull(timeoutOverride)) {
      long timeoutMillis = Math.max(
          1L, Math.min(timeoutOverride.toMillis(), config.getHttpTimeout().toMillis()));
      call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }
    try (Response resp = call.execute()) {
      ResponseBody responseBody = resp.body();
      byte[] responseBytes = responseBody.bytes();
      if (resp.code() >= 200 && resp.code() < 300) {
        if (responseType == Void.class || EmptyChecks.isEmpty(responseBytes)) {
          return null;
        }
        return objectMapper.readValue(responseBytes, responseType);
      }
      // errBody 不进 exception message — 避免错误链一路打 INFO/WARN 时把平台错误 payload 写满日志,
      // 也防止 token / 敏感字段泄露。完整 body 仅 DEBUG 级输出,排障开 DEBUG 看。见 #SDK-P1-3。
      if (log.isDebugEnabled() && !EmptyChecks.isEmpty(responseBytes)) {
        String errBody = new String(responseBytes, StandardCharsets.UTF_8);
        log.debug(
            "non-2xx response: status={} url={} body={}", resp.code(), url, truncate(errBody, 500));
      }
      throw new PlatformHttpException(resp.code(), "HTTP " + resp.code() + " from " + url);
    }
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
