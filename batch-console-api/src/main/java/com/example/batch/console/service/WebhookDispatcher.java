package com.example.batch.console.service;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.domain.param.WebhookDeliveryLogInsertParam;
import com.example.batch.console.mapper.ConsoleWebhookDeliveryLogMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Webhook 异步分发器。
 *
 * <p>事件先按订阅写入 {@code webhook_delivery_log(PENDING,next_retry_at=now)}，再进进程内队列做 burst 投递。队列满时使用
 * {@link CallerRunsPolicy}，进程重启/Pod kill 后由 {@link WebhookDeliveryRelay} 扫描 PENDING/EXHAUSTED
 * 行接力，避免事件在入队后无审计地丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcher {

  private static final int MAX_ATTEMPTS = 3;

  /** 有界队列容量 — 防止流量尖峰下内存积压。 */
  private static final int QUEUE_CAPACITY = 1024;

  /**
   * PENDING/EXHAUSTED 行 → relay 接力的初始退避:5 分钟后允许 {@link WebhookDeliveryRelay} 重投。 与 ADR §5.11
   * 配合,本类只做进程内 burst,持久化重试由 relay 接管。
   */
  private static final long INITIAL_RELAY_DELAY_SECONDS = 5L * 60L;

  private final ConsoleWebhookService webhookService;
  private final ConsoleWebhookDeliveryLogMapper deliveryLogRepository;

  /** P2-1(2026-05-16):见 OrchestratorInternalRestClient 同名字段注释,改 ObjectProvider 避免复用 prototype。 */
  private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

  // 5.11: 改用有界队列 + CallerRunsPolicy 替代无界队列
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          4,
          4,
          60L,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(QUEUE_CAPACITY),
          runnable -> {
            Thread thread = new Thread(runnable, "console-webhook-dispatch");
            thread.setDaemon(true);
            return thread;
          },
          new CallerRunsPolicy());

  public void dispatchAsync(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Object data,
      Instant emittedAt) {
    List<PendingWebhookDelivery> pendingDeliveries =
        persistPendingDeliveries(tenantId, eventType, stream, cursor, data, emittedAt);
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

  @PreDestroy
  public void shutdown() {
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
    } catch (RestClientResponseException ex) {
      SwallowedExceptionLogger.info(
          WebhookDispatcher.class, "catch:RestClientResponseException", ex);

      return WebhookDeliveryResult.failure(
          ex.getStatusCode().value(), sanitize(ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      return WebhookDeliveryResult.failure(null, sanitize(ex.getMessage()));
    }
  }

  private void deliver(
      WebhookSubscriptionEntity subscription, WebhookEventPayload payload, String payloadJson)
      throws UnknownHostException {
    // S-2.6: 分发前二次 DNS 解析并校验 IP，防止 rebinding 到内网
    String callbackHost = URI.create(subscription.getCallbackUrl()).getHost();
    DnsResolveGuard.resolveAndValidate(callbackHost);

    // 5.11: 显式设置 HTTP 超时，避免慢回调长期占用线程
    RestClient client =
        restClientBuilderProvider
            .getObject()
            .requestFactory(
                new SimpleClientHttpRequestFactory() {
                  {
                    setConnectTimeout(Duration.ofSeconds(5));
                    setReadTimeout(Duration.ofSeconds(10));
                  }
                })
            .build();
    RestClient.RequestBodySpec spec =
        client
            .post()
            .uri(subscription.getCallbackUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Batch-Tenant-Id", payload.tenantId())
            .header("X-Batch-Event-Type", payload.eventType())
            .header("X-Batch-Event-Stream", payload.stream() == null ? "" : payload.stream());
    if (subscription.getSecret() != null && !subscription.getSecret().isBlank()) {
      spec = spec.header("X-Batch-Signature", sign(payloadJson, subscription.getSecret()));
    }
    spec.body(payloadJson).retrieve().toBodilessEntity();
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
