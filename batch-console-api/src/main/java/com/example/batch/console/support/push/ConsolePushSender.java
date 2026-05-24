package com.example.batch.console.support.push;

import com.example.batch.console.config.ConsolePushProperties;
import com.example.batch.console.domain.entity.ConsolePushSubscriptionEntity;
import com.example.batch.console.mapper.ConsolePushSubscriptionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.security.Security;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
// web-push 5.1.1 内部用 Apache HttpClient 4 异步客户端;同步 send() 阻塞等待返回 HttpResponse。
import org.apache.http.HttpResponse;
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
   * 决定,默认无显式上限,慢 endpoint 可占住整个 {@code pushTaskExecutor} 工作线程。 这里用 {@code sendAsync().get(3s)}
   * 显式封顶;超时只 warn + 跳过本次,不立即清理 sub。 升级 web-push 6.x 后切换到 reactive 流式 API。
   */
  private static final long SEND_TIMEOUT_SECONDS = 3L;

  private final ConsolePushProperties properties;
  private final ConsolePushSubscriptionMapper repository;
  private final ObjectMapper objectMapper;

  private volatile PushService pushService;

  /** 单条推送 payload。{@code tag} 用于通知折叠;{@code url} 是点击后导航的 PWA 路由。 */
  public record PushPayload(String title, String body, String tag, String url) {}

  @PostConstruct
  void init() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    if (properties.isEnabled()
        && properties.getPublicKey() != null
        && properties.getPrivateKey() != null) {
      try {
        this.pushService =
            new PushService(properties.getPublicKey(), properties.getPrivateKey())
                .setSubject(properties.getSubject());
        log.info("[push] ConsolePushSender initialized, subject={}", properties.getSubject());
      } catch (Exception e) {
        log.error("[push] init failed; push disabled", e);
        this.pushService = null;
      }
    } else {
      log.info("[push] disabled (console.push.enabled=false or keys missing)");
    }
  }

  /** 给某租户某用户全部设备推送。失败的 endpoint 自动按规则清理。 */
  @Async("pushTaskExecutor")
  public void sendToUser(String tenantId, String username, PushPayload payload) {
    if (pushService == null) return;
    List<ConsolePushSubscriptionEntity> subs = repository.findByTenantAndUser(tenantId, username);
    sendBatch(subs, payload);
  }

  /** 给整个租户全部用户全部设备广播(罕用,系统级公告) */
  @Async("pushTaskExecutor")
  public void broadcastToTenant(String tenantId, PushPayload payload) {
    if (pushService == null) return;
    List<ConsolePushSubscriptionEntity> subs = repository.findByTenant(tenantId);
    sendBatch(subs, payload);
  }

  private void sendBatch(List<ConsolePushSubscriptionEntity> subs, PushPayload payload) {
    if (subs.isEmpty()) return;
    byte[] body;
    try {
      body =
          objectMapper.writeValueAsBytes(
              Map.of(
                  "title", payload.title() == null ? "" : payload.title(),
                  "body", payload.body() == null ? "" : payload.body(),
                  "tag", payload.tag() == null ? "" : payload.tag(),
                  "url", payload.url() == null ? "/m/ops/summary" : payload.url()));
    } catch (Exception e) {
      log.error("[push] payload serialize failed", e);
      return;
    }
    for (ConsolePushSubscriptionEntity sub : subs) {
      sendOne(sub, body);
    }
  }

  private void sendOne(ConsolePushSubscriptionEntity sub, byte[] body) {
    try {
      // web-push 5.1.1 没有 .subscription(Subscription) builder 方法;直接用
      // (endpoint, userPublicKey base64-url, userAuth base64-url, body, ttl)
      // 5-arg 构造器,免去 Subscription / Keys 包装。
      Notification notification =
          new Notification(
              sub.getEndpoint(),
              sub.getP256dhKey(),
              sub.getAuthSecret(),
              body,
              properties.getTtlSeconds());

      // sendOne 已在 pushTaskExecutor,但 web-push 5.1.x 同步 send() 没有显式超时,
      // 极端慢 endpoint 会占住整个 @Async 线程。改 sendAsync().get(3s) 封顶等待。
      Future<HttpResponse> future = pushService.sendAsync(notification);
      HttpResponse resp = future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      int code = resp.getStatusLine().getStatusCode();
      if (code >= 200 && code < 300) {
        repository.touchLastPushedAt(sub.getId(), Instant.now());
      } else if (code == 404 || code == 410) {
        // 浏览器推送服务告知:此 endpoint 已永久失效 → 物理删除
        log.info(
            "[push] endpoint gone ({}), purging sub id={} tenant={} user={}",
            code,
            sub.getId(),
            sub.getTenantId(),
            sub.getUsername());
        repository.deleteAllByEndpoint(sub.getEndpoint());
      } else {
        log.warn(
            "[push] send non-2xx status={} sub_id={} endpoint={}",
            code,
            sub.getId(),
            sub.getEndpoint());
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (TimeoutException te) {
      // 慢 endpoint:本次跳过、不重试、不立刻清理 sub(避免短瞬抖动误删),只 warn 留痕。
      // web-push 6.x 升级后可引入「连续 N 次超时 → 暂时禁用 endpoint」更精细策略。
      log.warn(
          "[push] send timeout after {}s, sub_id={} endpoint={}",
          SEND_TIMEOUT_SECONDS,
          sub.getId(),
          sub.getEndpoint());
    } catch (ExecutionException | RuntimeException e) {
      log.error("[push] send failed sub_id={} endpoint={}", sub.getId(), sub.getEndpoint(), e);
    } catch (Exception e) {
      // web-push 抛 JoseException / GeneralSecurityException / IOException 等 checked exception 兜底
      log.error("[push] send unexpected sub_id={} endpoint={}", sub.getId(), sub.getEndpoint(), e);
    }
  }
}
