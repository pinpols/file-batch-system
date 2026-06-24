package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * EMAIL（SMTP）通知发送器，{@link NotificationSender} 的可插拔实现，由 {@link NotificationSenderRegistry} 按
 * channelType 路由。基于 Spring {@link JavaMailSender}（{@code spring-boot-starter-mail}）发送纯文本邮件。
 *
 * <p><b>容错装配</b>：JavaMailSender bean 仅在配置了 {@code spring.mail.*} 时由 Spring Boot 自动装配；未配置时不存在。 因此本
 * sender 用 {@link ObjectProvider} 注入而非直接注入 {@link JavaMailSender}，无 SMTP 配置时不让应用启动失败， 仅在 {@link
 * #send} 时返回 {@code failure("mail not configured")}（不抛异常，与其余 sender 一致折叠失败）。
 *
 * <p><b>config_json 字段约定</b>（无正式 schema）：{@code to}（逗号分隔收件人，必填）、{@code subject}（可选， 缺省按 eventType
 * 生成）、{@code from}（可选，缺省由 SMTP 默认发件人决定）。
 *
 * <p><b>日志净化</b>：失败/告警日志只打收件人数量，绝不打收件人地址明文。
 */
@Slf4j
@Component
public class EmailNotificationSender implements NotificationSender {

  private static final String CHANNEL_TYPE_EMAIL = "EMAIL";

  private static final String CONFIG_KEY_TO = "to";
  private static final String CONFIG_KEY_SUBJECT = "subject";
  private static final String CONFIG_KEY_FROM = "from";

  /** 邮件正文最大字符数，超出截断（payloadJson 可能很大，避免超长正文）。 */
  private static final int MAX_BODY_LENGTH = 8192;

  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final ObjectMapper objectMapper;

  public EmailNotificationSender(
      ObjectProvider<JavaMailSender> mailSenderProvider, ObjectMapper objectMapper) {
    this.mailSenderProvider = mailSenderProvider;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean supports(String channelType) {
    return CHANNEL_TYPE_EMAIL.equalsIgnoreCase(channelType);
  }

  @Override
  public WebhookDeliveryResult send(NotificationMessage message) {
    Map<String, Object> config = parseConfig(message.configJson());

    String[] recipients = parseRecipients(str(config, CONFIG_KEY_TO));
    if (recipients.length == 0) {
      return WebhookDeliveryResult.failure(null, "missing email recipients");
    }

    JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
    if (mailSender == null) {
      log.warn(
          "EMAIL channel selected but JavaMailSender not configured; skipping:"
              + " channelCode={}, recipients={}",
          message.channelCode(),
          recipients.length);
      return WebhookDeliveryResult.failure(null, "mail not configured");
    }

    SimpleMailMessage mailMessage = new SimpleMailMessage();
    mailMessage.setTo(recipients);
    mailMessage.setSubject(resolveSubject(str(config, CONFIG_KEY_SUBJECT), message));
    mailMessage.setText(buildBody(message));
    String from = str(config, CONFIG_KEY_FROM);
    if (from != null && !from.isBlank()) {
      mailMessage.setFrom(from);
    }

    try {
      doSend(mailSender, mailMessage);
      return WebhookDeliveryResult.ok();
    } catch (MailException ex) {
      log.warn(
          "EMAIL delivery failed: channelCode={}, recipients={}, cause={}",
          message.channelCode(),
          recipients.length,
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, ex.getClass().getSimpleName());
    }
  }

  /** 抽出实际发送动作便于单测覆盖（子类可重写 / mock JavaMailSender）。 */
  protected void doSend(JavaMailSender mailSender, SimpleMailMessage message) {
    mailSender.send(message);
  }

  private String resolveSubject(String configured, NotificationMessage message) {
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String eventType = message.payload() == null ? null : message.payload().eventType();
    return "[batch] " + (eventType == null || eventType.isBlank() ? "notification" : eventType);
  }

  private String buildBody(NotificationMessage message) {
    String eventType = message.payload() == null ? null : message.payload().eventType();
    StringBuilder body = new StringBuilder();
    body.append("eventType: ").append(eventType == null ? "UNKNOWN" : eventType).append('\n');
    String payloadJson = message.payloadJson();
    if (payloadJson != null && !payloadJson.isBlank()) {
      body.append(truncate(payloadJson));
    }
    return body.toString();
  }

  private String truncate(String text) {
    if (text.length() <= MAX_BODY_LENGTH) {
      return text;
    }
    return text.substring(0, MAX_BODY_LENGTH) + "...[truncated]";
  }

  private String[] parseRecipients(String to) {
    if (to == null || to.isBlank()) {
      return new String[0];
    }
    return Arrays.stream(to.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toArray(String[]::new);
  }

  private Map<String, Object> parseConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException | JsonProcessingException ex) {
      log.warn(
          "EMAIL channel config_json parse failed; treating as empty config: cause={}",
          ex.getClass().getSimpleName());
      return Map.of();
    }
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }
}
