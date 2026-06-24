package io.github.pinpols.batch.console.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知投递安全配置。
 *
 * <p>{@code emailAllowedDomains}：邮件收件人域名白名单。<b>空 = 不限制</b>（允许任意域名）；非空时只允许这些域名的收件人，
 * 其余被过滤——防止把告警邮件发往任意外部地址(邮件轰炸 / 数据外泄到非受信邮箱)。比对大小写不敏感、按 {@code @} 后缀匹配。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.notification")
public class NotificationProperties {

  /** 邮件收件人域名白名单(不含 @);空表示不限制。例:["example.com","corp.internal"]。 */
  private List<String> emailAllowedDomains = List.of();
}
