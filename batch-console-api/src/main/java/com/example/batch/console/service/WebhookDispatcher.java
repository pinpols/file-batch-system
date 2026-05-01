package com.example.batch.console.service;

import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.repository.ConsoleWebhookDeliveryLogRepository;
import com.example.batch.console.repository.WebhookDeliveryLogInsertParam;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Webhook 异步分发器（Best-Effort 语义）。
 *
 * <p>进程内有界队列 + 最多 3 次重试 + delivery log 审计。
 *
 * <p><strong>注意</strong>：本实现为 best-effort 通知，不保证可靠交付：
 *
 * <ul>
 *   <li>进程重启时未消费的 webhook 会丢失
 *   <li>队列满时新 webhook 会被丢弃（记录 WARN 日志）
 *   <li>如需可靠交付，应改用 outbox + 持久化队列
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcher {

  private static final int MAX_ATTEMPTS = 3;

  /** 有界队列容量 — 防止流量尖峰下内存积压。 */
  private static final int QUEUE_CAPACITY = 1024;

  /**
   * EXHAUSTED 行 → relay 接力的初始退避:5 分钟后允许 {@link WebhookDeliveryRelay} 重投。 与 ADR §5.11 配合,本类只做 进程内
   * best-effort burst,持久化重试由 relay 接管。
   */
  private static final long INITIAL_RELAY_DELAY_SECONDS = 5L * 60L;

  private final ConsoleWebhookService webhookService;
  private final ConsoleWebhookDeliveryLogRepository deliveryLogRepository;
  private final RestClient.Builder restClientBuilder;
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
          });

  public void dispatchAsync(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Object data,
      Instant emittedAt) {
    try {
      executor.submit(() -> dispatch(tenantId, eventType, stream, cursor, data, emittedAt));
    } catch (RejectedExecutionException e) {
      log.warn(
          "webhook dispatch queue full, dropping event: tenant={}, eventType={}",
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
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void dispatch(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Object data,
      Instant emittedAt) {
    List<WebhookSubscriptionEntity> subscriptions =
        webhookService.findEnabledSubscriptions(tenantId);
    if (subscriptions == null || subscriptions.isEmpty()) {
      return;
    }
    WebhookEventPayload payload =
        new WebhookEventPayload(
            tenantId,
            normalizeEventType(eventType),
            stream,
            cursor,
            emittedAt == null ? Instant.now() : emittedAt,
            data);
    String payloadJson = JsonUtils.toJson(payload);
    for (WebhookSubscriptionEntity subscription : subscriptions) {
      if (subscription == null
          || subscription.getId() == null
          || !matches(subscription.getEventTypes(), payload.eventType())) {
        continue;
      }
      deliverWithRetry(subscription, payload, payloadJson);
    }
  }

  private void deliverWithRetry(
      WebhookSubscriptionEntity subscription, WebhookEventPayload payload, String payloadJson) {
    long backoffMillis = 250L;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      WebhookDeliveryResult result = attemptDelivery(subscription, payload, payloadJson);

      if (result.success()) {
        WebhookDeliveryLogInsertParam successLog =
            WebhookDeliveryLogInsertParam.builder()
                .tenantId(payload.tenantId())
                .subscriptionId(subscription.getId())
                .eventType(payload.eventType())
                .payloadJson(payloadJson)
                .deliveryStatus("SUCCESS")
                .attempt(attempt)
                .build();
        deliveryLogRepository.insert(successLog);
        return;
      }

      boolean exhausted = attempt >= MAX_ATTEMPTS;
      String deliveryStatus = exhausted ? "EXHAUSTED" : "FAILED";
      // ADR §5.11: 仅 EXHAUSTED 行设 next_retry_at,relay 据此扫描接力重投;
      // FAILED 行属本轮 burst 中间态,不需要 relay 介入。
      Instant nextRetryAt =
          exhausted ? Instant.now().plusSeconds(INITIAL_RELAY_DELAY_SECONDS) : null;
      WebhookDeliveryLogInsertParam failureLog =
          WebhookDeliveryLogInsertParam.builder()
              .tenantId(payload.tenantId())
              .subscriptionId(subscription.getId())
              .eventType(payload.eventType())
              .payloadJson(payloadJson)
              .httpStatus(result.httpStatus())
              .responseBody(result.errorSummary())
              .deliveryStatus(deliveryStatus)
              .attempt(attempt)
              .nextRetryAt(nextRetryAt)
              .build();
      deliveryLogRepository.insert(failureLog);
      if (attempt < MAX_ATTEMPTS) {
        sleep(backoffMillis);
        backoffMillis *= 2;
      }
    }
  }

  /**
   * 单次 HTTP 投递,捕获所有异常并返回结构化结果。
   *
   * <p>同时供 {@link WebhookDispatcher#deliverWithRetry burst-retry} 与 {@link WebhookDeliveryRelay} 共用
   * — 两侧都不应自己 catch 网络异常,统一从 {@link WebhookDeliveryResult} 决策。
   */
  public WebhookDeliveryResult attemptDelivery(
      WebhookSubscriptionEntity subscription, WebhookEventPayload payload, String payloadJson) {
    try {
      deliver(subscription, payload, payloadJson);
      return WebhookDeliveryResult.ok();
    } catch (RestClientResponseException ex) {
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
        restClientBuilder
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
      Thread.currentThread().interrupt();
    }
  }
}
