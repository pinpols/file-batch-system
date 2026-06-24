package io.github.pinpols.batch.console.domain.notification.service;

import java.util.List;

/**
 * 短信发送 provider SPI（可插拔，aliyun / tencent / twilio）。由 {@code batch.console.sms.provider} 选装,
 * 任一时刻最多一个 bean(各实现 {@code @ConditionalOnProperty})。{@link SmsNotificationSender}(channelType=SMS)
 * 解析手机号后委托给当前 provider;接新厂商 = 新增一个本接口实现,不动分发与 sender。
 *
 * <p>provider 各自从 {@code message.configJson()}(签名/模板等 per-channel 参数)与 {@code
 * SmsProperties}(AK/SK/token 等后端凭证)取所需;失败折叠成 {@link WebhookDeliveryResult#failure} 而非抛异常。
 */
public interface SmsProvider {

  /** 是否为该 provider 名(aliyun/tencent/twilio,大小写不敏感)。 */
  boolean supports(String provider);

  /** 向手机号列表发送一条短信(文案/模板参数由 provider 按 message 与自身约定构造)。 */
  WebhookDeliveryResult send(List<String> phoneNumbers, NotificationMessage message);
}
