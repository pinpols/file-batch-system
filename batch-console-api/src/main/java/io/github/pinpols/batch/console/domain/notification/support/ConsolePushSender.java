package io.github.pinpols.batch.console.domain.notification.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import org.asynchttpclient.Response;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 后台 Web Push 发送器。
 *
 * <p>用法(从告警 / 审批 / Catch-up 服务调):
 *
 * <pre>
 *   pushSender.sendToUser(
 *       tenantId, username,
 *       new PushPayload("[CRITICAL] Job ETL_01 failed",
 *                       "tenant=ta · 2 retries exhausted",
 *                       "alert-" + alertId,
 *                       "/m/alerts?id=" + alertId));
 * </pre>
 *
 * <p>发送是 @Async,不阻塞调用方;失败按规则清理。
 *
 * <p>web-push 库内部用 BouncyCastle 做 ECIES,这里 {@link #init()} 显式注册 BC provider。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsolePushSender {

  /**
   * P1(2026-05-23 audit):单次 push 投递的最大等待秒数。web-push 5.1.x 同步 send() 阻塞由底层 Apache HttpClient 4
   * 决定,默认无显式上限,慢 endpoint 可占住整个 {@code pushTaskExecutor} 工作线程。 这里用 async send + get(3s) 显式封顶;超时只
   * warn + 跳过本次,不立即清理 sub。
   */
  private static final long SEND_TIMEOUT_SECONDS = 3L;

  private final ConsolePushProperties properties;
  private final ConsolePushSubscriptionMapper repository;
  private final ObjectMapper objectMapper;

  private final AtomicReference<ManagedWebPushClient> pushService = new AtomicReference<>();

  /** 单条推送 payload。{@code tag} 用于通知折叠;{@code url} 是点击后导航的 PWA 路由。 */
  public record PushPayload(String title, String body, String tag, String url) {}

  /** 推送服务已接受请求，并不能证明浏览器显示或读取了通知。 */
  public enum DeliveryOutcome {
    ACCEPTED,
    GONE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    UNKNOWN,
    NOT_READY
  }

  public record DeliveryResult(DeliveryOutcome outcome, Integer httpStatus) {}

  /** 供拥有持久化投递状态的调用方执行的同步、有界单设备尝试。 */
  public DeliveryResult sendToSubscription(ConsolePushSubscriptionEntity sub, PushPayload payload) {
    if (EmptyChecks.isNull(pushService.get())) {
      return new DeliveryResult(DeliveryOutcome.NOT_READY, null);
    }
    if (EmptyChecks.isNull(sub) || EmptyChecks.isNull(payload)) {
      return new DeliveryResult(DeliveryOutcome.PERMANENT_FAILURE, null);
    }
    try {
      byte[] serializedPayload = serializePayload(payload);
      return sendOne(sub, serializedPayload);
    } catch (Exception e) {
      log.error("[push] payload serialize failed: {}", SwallowedExceptionLogger.summary(e));
      return new DeliveryResult(DeliveryOutcome.PERMANENT_FAILURE, null);
    }
  }

  @PostConstruct
  void init() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    boolean pushSigningKeysAreConfigured = properties.isEnabled()
        && properties.getPublicKey() != null
        && properties.getPrivateKey() != null;
    if (pushSigningKeysAreConfigured) {
      try {
        this.pushService.set(new ManagedWebPushClient(
                properties.getPublicKey(),
                properties.getPrivateKey(),
                Duration.ofSeconds(SEND_TIMEOUT_SECONDS))
            .setSubject(properties.getSubject()));
        log.info("[push] ConsolePushSender initialized, subject={}", properties.getSubject());
      } catch (Exception e) {
        log.error("[push] init failed; push disabled", e);
        this.pushService.set(null);
      }
    } else {
      log.info("[push] disabled (console.push.enabled=false or keys missing)");
    }
  }

  @PreDestroy
  void close() {
    ManagedWebPushClient service = pushService.getAndSet(null);
    if (EmptyChecks.isNull(service)) return;
    try {
      service.close();
    } catch (IOException failure) {
      log.warn("[push] transport close failed: {}", SwallowedExceptionLogger.summary(failure));
    }
  }

  /** 给某租户某用户全部设备推送。失败的 endpoint 自动按规则清理。 */
  @Async("pushTaskExecutor")
  public void sendToUser(String tenantId, String username, PushPayload payload) {
    if (pushService.get() == null) return;
    List<ConsolePushSubscriptionEntity> subs = repository.findByTenantAndUser(tenantId, username);
    sendBatch(subs, payload);
  }

  /** 给整个租户全部用户全部设备广播(罕用,系统级公告) */
  @Async("pushTaskExecutor")
  public void broadcastToTenant(String tenantId, PushPayload payload) {
    if (pushService.get() == null) return;
    List<ConsolePushSubscriptionEntity> subs = repository.findByTenant(tenantId);
    sendBatch(subs, payload);
  }

  private void sendBatch(List<ConsolePushSubscriptionEntity> subs, PushPayload payload) {
    if (subs.isEmpty()) return;
    byte[] body;
    try {
      body = serializePayload(payload);
    } catch (Exception e) {
      log.error("[push] payload serialize failed", e);
      return;
    }
    for (ConsolePushSubscriptionEntity sub : subs) {
      sendOne(sub, body);
    }
  }

  private byte[] serializePayload(PushPayload payload) throws JsonProcessingException {
    Map<String, String> serializedPayload = Map.of(
        "title", EmptyChecks.isNull(payload.title()) ? "" : payload.title(),
        "body", EmptyChecks.isNull(payload.body()) ? "" : payload.body(),
        "tag", EmptyChecks.isNull(payload.tag()) ? "" : payload.tag(),
        "url", EmptyChecks.isNull(payload.url()) ? "/m/ops/summary" : payload.url());
    return objectMapper.writeValueAsBytes(serializedPayload);
  }

  private DeliveryResult sendOne(ConsolePushSubscriptionEntity sub, byte[] body) {
    if (Thread.currentThread().isInterrupted()) {
      return new DeliveryResult(DeliveryOutcome.UNKNOWN, null);
    }
    ManagedWebPushClient service = pushService.get();
    if (EmptyChecks.isNull(service)) {
      return new DeliveryResult(DeliveryOutcome.NOT_READY, null);
    }
    Notification notification;
    try {
      // web-push 5.1.1 没有 .subscription(Subscription) builder 方法;直接用
      // (endpoint, userPublicKey base64-url, userAuth base64-url, body, ttl)
      // 5-arg 构造器,免去 Subscription / Keys 包装。
      notification = new Notification(
          sub.getEndpoint(),
          sub.getP256dhKey(),
          sub.getAuthSecret(),
          body,
          properties.getTtlSeconds());

    } catch (Exception e) {
      log.error(
          "[push] invalid subscription id={} error={}",
          sub.getId(),
          SwallowedExceptionLogger.summary(e));
      return new DeliveryResult(DeliveryOutcome.PERMANENT_FAILURE, null);
    }
    try {
      CompletableFuture<Response> future = service.send(notification);
      Response resp = future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      int code = resp.getStatusCode();
      if (code >= 200 && code < 300) {
        updateSubscriptionAfterResponse(sub, code);
        return new DeliveryResult(DeliveryOutcome.ACCEPTED, code);
      } else if (code == 404 || code == 410) {
        updateSubscriptionAfterResponse(sub, code);
        return new DeliveryResult(DeliveryOutcome.GONE, code);
      }
      log.warn("[push] send non-2xx status={} sub_id={}", code, sub.getId());
      return new DeliveryResult(
          code == 429 || code >= 500
              ? DeliveryOutcome.RETRYABLE_FAILURE
              : DeliveryOutcome.PERMANENT_FAILURE,
          code);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (TimeoutException te) {
      // 服务方可能已经接受请求；超时并不代表请求被拒绝。
      log.warn("[push] send timeout after {}s, sub_id={}", SEND_TIMEOUT_SECONDS, sub.getId());
    } catch (ExecutionException | RuntimeException e) {
      log.error(
          "[push] send failed sub_id={} error={}",
          sub.getId(),
          SwallowedExceptionLogger.summary(e));
    } catch (Exception e) {
      // web-push 抛 JoseException / GeneralSecurityException / IOException 等 checked exception 回退
      log.error(
          "[push] send unexpected sub_id={} error={}",
          sub.getId(),
          SwallowedExceptionLogger.summary(e));
    }
    return new DeliveryResult(DeliveryOutcome.UNKNOWN, null);
  }

  private void updateSubscriptionAfterResponse(ConsolePushSubscriptionEntity sub, int code) {
    try {
      if (code >= 200 && code < 300) {
        repository.touchIfUnchanged(sub, Instant.now());
      } else {
        repository.deleteIfUnchanged(sub);
      }
    } catch (RuntimeException e) {
      // 本地记账不能把已经观察到的服务方响应改成重试。
      log.warn(
          "[push] subscription bookkeeping failed status={} sub_id={} error={}",
          code,
          sub.getId(),
          SwallowedExceptionLogger.summary(e));
    }
  }
}
