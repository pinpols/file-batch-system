package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;

/**
 * 通知渠道发送 SPI（可插拔，开闭原则）。新增渠道 = 新增一个本接口实现，不动分发编排。
 *
 * <p>WEBHOOK 渠道仍走既有 {@link WebhookDispatcher}（带持久化投递日志）专用路径；本 SPI 覆盖其余渠道 （DINGTALK / WECOM 企业微信 /
 * SLACK / EMAIL / SMS 等），由 {@link NotificationSenderRegistry} 按 channelType 路由。
 *
 * <p>实现要求：无状态、线程安全（单例 bean，并发分发共享）；所有失败折叠成 {@link WebhookDeliveryResult#failure} 而非抛异常，便于上层统一重试。
 */
public interface NotificationSender {

  /** 唯一渠道类型键（对齐 {@code NotificationChannelType} 的 code）。 */
  String channelType();

  /** 是否处理该 channelType；保留为调用端便利方法，注册表以 {@link #channelType()} 做唯一性校验。 */
  default boolean supports(String candidate) {
    return EmptyChecks.isNotNull(candidate) && channelType().equalsIgnoreCase(candidate);
  }

  /** 同步投递一条消息，返回结构化结果（不抛异常）。 */
  WebhookDeliveryResult send(NotificationMessage message);
}
