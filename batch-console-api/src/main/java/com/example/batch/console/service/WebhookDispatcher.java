package com.example.batch.console.service;

import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.repository.ConsoleWebhookDeliveryLogRepository;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Webhook 异步分发器。
 *
 * <p>MVP 版本：进程内异步线程池 + 最多 3 次重试；每次尝试都落 delivery log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcher {

  private static final int MAX_ATTEMPTS = 3;

  private final ConsoleWebhookService webhookService;
  private final ConsoleWebhookDeliveryLogRepository deliveryLogRepository;
  private final RestClient.Builder restClientBuilder;
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          4,
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
    executor.submit(() -> dispatch(tenantId, eventType, stream, cursor, data, emittedAt));
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
      Integer httpStatus = null;
      String responseBody = null;
      boolean success = false;
      try {
        deliver(subscription, payload, payloadJson);
        success = true;
      } catch (RestClientResponseException ex) {
        httpStatus = ex.getStatusCode().value();
        responseBody = sanitize(ex.getResponseBodyAsString());
      } catch (Exception ex) {
        responseBody = sanitize(ex.getMessage());
      }

      if (success) {
        insertLog(subscription, payload, payloadJson, null, null, "SUCCESS", attempt);
        return;
      }

      String deliveryStatus = attempt >= MAX_ATTEMPTS ? "EXHAUSTED" : "FAILED";
      insertLog(
          subscription, payload, payloadJson, httpStatus, responseBody, deliveryStatus, attempt);
      if (attempt < MAX_ATTEMPTS) {
        sleep(backoffMillis);
        backoffMillis *= 2;
      }
    }
  }

  private void deliver(
      WebhookSubscriptionEntity subscription, WebhookEventPayload payload, String payloadJson) {
    RestClient client = restClientBuilder.build();
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

  private void insertLog(
      WebhookSubscriptionEntity subscription,
      WebhookEventPayload payload,
      String payloadJson,
      Integer httpStatus,
      String responseBody,
      String deliveryStatus,
      int attempt) {
    deliveryLogRepository.insert(
        payload.tenantId(),
        subscription.getId(),
        payload.eventType(),
        payloadJson,
        httpStatus,
        responseBody,
        deliveryStatus,
        attempt);
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

  private record WebhookEventPayload(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Instant emittedAt,
      Object data) {}
}
