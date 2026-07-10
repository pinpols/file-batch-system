package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.param.WebhookDeliveryLogInsertParam;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Webhook 异步分发器。
 *
 * <p>事件先按订阅写入 {@code webhook_delivery_log(PENDING,next_retry_at=now)}，再进进程内队列做 burst 投递。
 * P1(2026-05-23 audit):队列满时改用 {@link AbortPolicy} 显式抛 {@link RejectedExecutionException}, 拒绝任务由
 * {@link WebhookDeliveryRelay} 扫 {@code PENDING/EXHAUSTED} 行接力(日志已先于入队写入), 避免之前 {@code
 * CallerRunsPolicy} 把投递反压回 Tomcat 工作线程(单次 webhook 8-10s 超时直接拖死请求线程池)。
 */
@Slf4j
@Service
public class WebhookDispatcher {

  private static final int MAX_ATTEMPTS = 3;

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  /** 有界队列容量 — 防止流量尖峰下内存积压。 */
  private static final int QUEUE_CAPACITY = 1024;

  /**
   * PENDING/EXHAUSTED 行 → relay 接力的初始退避:5 分钟后允许 {@link WebhookDeliveryRelay} 重投。 与 ADR §5.11
   * 配合,本类只做进程内 burst,持久化重试由 relay 接管。
   */
  private static final long INITIAL_RELAY_DELAY_SECONDS = 5L * 60L;

  private final ConsoleWebhookService webhookService;
  private final ConsoleWebhookDeliveryLogMapper deliveryLogRepository;

  /**
   * OkHttp 客户端一次性构造并复用:内置 {@link SsrfGuardedDns} 在建连回调层做 per-request SSRF pin —— 连的就是 guard 校验过的 那个
   * IP,关闭 DNS-rebinding 的 TOCTOU 窗口,且 TLS 仍按 hostname 校验(SNI/证书不动)。 5.11:显式 connect/read/write +
   * callTimeout,避免慢回调长期占用线程。
   */
  private final OkHttpClient httpClient;

  public WebhookDispatcher(
      ConsoleWebhookService webhookService,
      ConsoleWebhookDeliveryLogMapper deliveryLogRepository,
      SsrfGuardedDns ssrfGuardedDns) {
    this.webhookService = webhookService;
    this.deliveryLogRepository = deliveryLogRepository;
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .dns(ssrfGuardedDns)
            .build();
  }

  // 5.11: 有界队列 + AbortPolicy(原 CallerRunsPolicy 会反压 Tomcat 请求线程,与 WebhookDeliveryRelay
  //   持久化补偿重叠,放弃即丢:实际是入队前已写 PENDING 日志,被拒任务由 relay 回退)
  private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
  private final AtomicBoolean stopping = new AtomicBoolean(false);

  private final ExecutorService executor =
      new ThreadPoolExecutor(
          4,
          4,
          60L,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(QUEUE_CAPACITY),
          runnable -> {
            Thread thread =
                new Thread(runnable, "console-webhook-dispatch-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          },
          new AbortPolicy());

  public void dispatchAsync(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Object data,
      Instant emittedAt) {
    if (stopping.get()) {
      log.info(
          "webhook dispatch skipped during shutdown: tenant={}, eventType={}", tenantId, eventType);
      return;
    }
    List<PendingWebhookDelivery> pendingDeliveries =
        persistPendingDeliveries(tenantId, eventType, stream, cursor, data, emittedAt);
    if (stopping.get()) {
      log.info(
          "webhook dispatch persisted but skipped enqueue during shutdown: tenant={}, eventType={}",
          tenantId,
          eventType);
      return;
    }
    try {
      for (PendingWebhookDelivery pending : pendingDeliveries) {
        executor.submit(() -> deliverPersisted(pending));
      }
    } catch (RejectedExecutionException e) {
      log.warn(
          "webhook dispatch rejected after PENDING persisted; relay will retry: tenant={},"
              + " eventType={}",
          tenantId,
          eventType);
    }
  }

  @EventListener(ContextClosedEvent.class)
  public void shutdownOnContextClosed(ContextClosedEvent event) {
    shutdown();
  }

  @PreDestroy
  public void shutdown() {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("webhook dispatcher did not terminate in 5 s; forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      SwallowedExceptionLogger.info(WebhookDispatcher.class, "catch:InterruptedException", ex);

      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private List<PendingWebhookDelivery> persistPendingDeliveries(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Object data,
      Instant emittedAt) {
    List<WebhookSubscriptionEntity> subscriptions =
        webhookService.findEnabledSubscriptions(tenantId);
    if (subscriptions == null || subscriptions.isEmpty()) {
      return List.of();
    }
    WebhookEventPayload payload =
        new WebhookEventPayload(
            tenantId,
            normalizeEventType(eventType),
            stream,
            cursor,
            emittedAt == null ? BatchDateTimeSupport.utcNow() : emittedAt,
            data);
    String payloadJson = JsonUtils.toJson(payload);
    List<PendingWebhookDelivery> pendingDeliveries = new ArrayList<>();
    for (WebhookSubscriptionEntity subscription : subscriptions) {
      if (subscription == null
          || subscription.getId() == null
          || !matches(subscription.getEventTypes(), payload.eventType())) {
        continue;
      }
      Long deliveryLogId =
          deliveryLogRepository.insertReturningId(
              WebhookDeliveryLogInsertParam.builder()
                  .tenantId(payload.tenantId())
                  .subscriptionId(subscription.getId())
                  .eventType(payload.eventType())
                  .payloadJson(payloadJson)
                  .deliveryStatus("PENDING")
                  .attempt(0)
                  .nextRetryAt(
                      BatchDateTimeSupport.utcNow().plusSeconds(INITIAL_RELAY_DELAY_SECONDS))
                  .build());
      pendingDeliveries.add(
          new PendingWebhookDelivery(deliveryLogId, subscription, payload, payloadJson));
    }
    return pendingDeliveries;
  }

  private void deliverPersisted(PendingWebhookDelivery pending) {
    long backoffMillis = 250L;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      WebhookDeliveryResult result =
          attemptDelivery(pending.subscription(), pending.payload(), pending.payloadJson());

      if (result.success()) {
        deliveryLogRepository.markRetrySuccess(
            pending.deliveryLogId(), attempt, result.httpStatus());
        return;
      }

      if (attempt < MAX_ATTEMPTS) {
        sleep(backoffMillis);
        backoffMillis *= 2;
      } else {
        deliveryLogRepository.markRetryFailure(
            pending.deliveryLogId(),
            attempt,
            result.httpStatus(),
            result.errorSummary(),
            BatchDateTimeSupport.utcNow().plusSeconds(INITIAL_RELAY_DELAY_SECONDS));
      }
    }
  }

  /**
   * 单次 HTTP 投递,捕获所有异常并返回结构化结果。
   *
   * <p>同时供 burst-retry 与 {@link WebhookDeliveryRelay} 共用 — 两侧都不应自己 catch 网络异常,统一从 {@link
   * WebhookDeliveryResult} 决策。
   */
  public WebhookDeliveryResult attemptDelivery(
      WebhookSubscriptionEntity subscription, WebhookEventPayload payload, String payloadJson) {
    try {
      deliver(subscription, payload, payloadJson);
      return WebhookDeliveryResult.ok();
    } catch (WebhookHttpStatusException ex) {
      SwallowedExceptionLogger.info(
          WebhookDispatcher.class, "catch:WebhookHttpStatusException", ex);

      return WebhookDeliveryResult.failure(ex.status(), sanitize(ex.responseBody()));
    } catch (Exception ex) {
      // 含 SsrfGuardedDns 抛出的 BlockedAddressException(rebinding 到内网被拦):投递失败,不建连。
      return WebhookDeliveryResult.failure(null, sanitize(ex.getMessage()));
    }
  }

  private void deliver(
      WebhookSubscriptionEntity subscription, WebhookEventPayload payload, String payloadJson)
      throws IOException {
    // 主机名 rebinding:由 httpClient 内置 SsrfGuardedDns 在建连回调层解析 + 校验 + pin(连的就是校验的那个 IP)。
    // 字面量 IP 兜底:OkHttp 对字面量 IP 短路不走 Dns,故这里补一次 guard 拦住 metadata/内网字面量 IP;
    // 对主机名这次是冗余预检(实连 IP 仍由 SsrfGuardedDns 决定,不重开 rebinding 窗口)。
    String host = URI.create(subscription.getCallbackUrl()).getHost();
    if (host != null) {
      DnsResolveGuard.resolveAndValidate(host);
    }
    Request.Builder builder =
        new Request.Builder()
            .url(subscription.getCallbackUrl())
            .header("X-Batch-Tenant-Id", payload.tenantId())
            .header("X-Batch-Event-Type", payload.eventType())
            .header("X-Batch-Event-Stream", payload.stream() == null ? "" : payload.stream())
            .post(RequestBody.create(payloadJson, JSON));
    if (subscription.getSecret() != null && !subscription.getSecret().isBlank()) {
      builder = builder.header("X-Batch-Signature", sign(payloadJson, subscription.getSecret()));
    }
    try (Response response = httpClient.newCall(builder.build()).execute()) {
      if (!response.isSuccessful()) {
        String body = response.body() == null ? "" : response.body().string();
        throw new WebhookHttpStatusException(response.code(), body);
      }
    }
  }

  /** 非 2xx 响应的结构化载体:把 HTTP 状态码 + 响应体带回 {@link #attemptDelivery} 决策(替代原 RestClient 的异常映射)。 */
  private static final class WebhookHttpStatusException extends IOException {
    private final int status;
    private final String responseBody;

    WebhookHttpStatusException(int status, String responseBody) {
      super("webhook responded with non-2xx status: " + status);
      this.status = status;
      this.responseBody = responseBody;
    }

    int status() {
      return status;
    }

    String responseBody() {
      return responseBody;
    }
  }

  private boolean matches(String configuredEventTypes, String eventType) {
    if (configuredEventTypes == null || configuredEventTypes.isBlank()) {
      return false;
    }
    if ("*".equals(configuredEventTypes.trim())) {
      return true;
    }
    return Arrays.stream(configuredEventTypes.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(value -> value.toUpperCase(Locale.ROOT))
        .anyMatch(value -> value.equals(eventType));
  }

  private String normalizeEventType(String eventType) {
    return eventType == null ? "UNKNOWN" : eventType.trim().toUpperCase(Locale.ROOT);
  }

  private String sign(String payloadJson, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
      return "sha256=" + HexFormat.of().formatHex(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to sign webhook payload", ex);
    }
  }

  private String sanitize(String text) {
    return ConsoleTextSanitizer.safeDisplay(text, 2048);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      SwallowedExceptionLogger.info(WebhookDispatcher.class, "catch:InterruptedException", ex);

      Thread.currentThread().interrupt();
    }
  }

  private record PendingWebhookDelivery(
      Long deliveryLogId,
      WebhookSubscriptionEntity subscription,
      WebhookEventPayload payload,
      String payloadJson) {}
}
