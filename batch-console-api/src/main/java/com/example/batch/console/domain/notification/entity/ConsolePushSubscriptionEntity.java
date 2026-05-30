package com.example.batch.console.domain.notification.entity;

import java.time.Instant;
import lombok.Data;

/**
 * PWA Web Push 订阅记录。
 *
 * <p>对应表 {@code batch.console_push_subscription}(V129)。
 *
 * <p>{@link #endpoint} / {@link #p256dhKey} / {@link #authSecret} 三元组直接来自浏览器 {@code
 * PushSubscription.toJSON()},服务端不解析,只在推送时透传给 web-push 库。
 */
@Data
public class ConsolePushSubscriptionEntity {

  private Long id;
  private String tenantId;
  private String username;
  private String endpoint;
  private String p256dhKey;
  private String authSecret;
  private String userAgent;
  private Instant createdAt;
  private Instant lastPushedAt;
  private Instant lastSeenAt;
}
