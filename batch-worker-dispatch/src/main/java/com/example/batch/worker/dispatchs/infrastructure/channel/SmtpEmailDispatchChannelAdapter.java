package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * SMTP 邮件邮件分发适配器，将 {@code file_record} 中的文件作为附件通过 SMTP 发送。
 *
 * <p><b>S-1.7 · 通道安全基线</b>：
 *
 * <ul>
 *   <li>附件大小硬上限：{@value #MAX_ATTACHMENT_BYTES} 字节（25MB），超限直接拒发
 *   <li>prod profile：强制 STARTTLS，渠道配置 {@code smtp_starttls=false} 被覆盖为 true
 *   <li>prod profile：强制 {@code mail.smtp.ssl.checkserveridentity=true}，防止中间人
 *   <li>mail_from / mail_to / mail_subject 做 CRLF header 注入剥离（{@link #sanitizeHeader}）
 *   <li>{@code smtp.splitlongparameters=false} 避免长 filename 被拆包触发编码歧义
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SmtpEmailDispatchChannelAdapter implements DispatchChannelAdapter {

  /** S-1.7：附件大小硬上限 25MB，覆盖大多数企业 SMTP 服务器的消息上限。 */
  private static final long MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L;

  private final DispatchFileContentResolver fileContentResolver;
  private final Environment environment;
  private final BatchSecurityProperties securityProperties;

  @Override
  public boolean supports(String channelType) {
    return channelType != null && "EMAIL".equalsIgnoreCase(channelType);
  }

  private record MailConfig(
      String host,
      int port,
      String smtpUser,
      String smtpPass,
      boolean startTls,
      String from,
      String to) {}

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();

    MailConfig mailConfig = resolveMailConfig(channelConfig);
    if (mailConfig == null) {
      String host = stringProp(channelConfig, "smtp_host");
      if (!Texts.hasText(host)) {
        return new DispatchResult(false, null, null, false, false, "smtp_host missing", null);
      }
      return new DispatchResult(
          false, null, null, false, false, "mail_from/mail_to or target_endpoint missing", null);
    }

    String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
    String externalRequestId =
        command.payload().externalRequestId() != null
                && !command.payload().externalRequestId().isBlank()
            ? command.payload().externalRequestId()
            : UUID.randomUUID().toString();
    String receiptCode =
        command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()
            ? command.payload().receiptCode()
            : "R-" + externalRequestId;
    boolean acknowledged =
        "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
    boolean pending =
        "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);

    try {
      MimeMessage message = buildMimeMessage(mailConfig, command, externalRequestId);
      // ⚠4 (2026-05-03): 改 ByteArrayDataSource 直接桥接 InputStream → SMTP socket. 之前 Files.copy 落 temp
      // file
      // 后 FileDataSource 又被 jakarta.mail 整块读到 socket = 2× 磁盘 IO. 25MB 上限已防 OOM.
      byte[] attachmentBytes = readBoundedAttachment(command.fileRecord());
      addAttachment(message, attachmentBytes, command.fileRecord());
      sendMail(mailConfig, message);
      return new DispatchResult(
          true,
          externalRequestId,
          receiptCode,
          acknowledged,
          pending,
          "sent via SMTP",
          "mailto:" + mailConfig.to());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(SmtpEmailDispatchChannelAdapter.class, "catch:Exception", ex);

      return new DispatchResult(
          false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
    }
  }

  private MailConfig resolveMailConfig(Map<String, Object> channelConfig) {
    String host = stringProp(channelConfig, "smtp_host");
    if (!Texts.hasText(host)) {
      return null;
    }
    int port = intProp(channelConfig, "smtp_port", 587);
    String smtpUser = stringProp(channelConfig, "smtp_username");
    String smtpPass = stringProp(channelConfig, "smtp_password");
    boolean startTls = boolProp(channelConfig, "smtp_starttls", true);
    // S-1.7：prod profile 强制 STARTTLS，覆盖渠道"关掉 TLS"的配置；dev/local 允许关闭
    if (isProductionProfile()) {
      startTls = true;
    }

    String from = firstNonBlank(stringProp(channelConfig, "mail_from"), smtpUser);
    String to =
        firstNonBlank(
            stringProp(channelConfig, "mail_to"), stringProp(channelConfig, "target_endpoint"));
    if (!Texts.hasText(from) || !Texts.hasText(to)) {
      return null;
    }
    return new MailConfig(host, port, smtpUser, smtpPass, startTls, from, to);
  }

  /** S-1.7：剥离 CR/LF 防 header 注入；长度截断到 RFC 5322 header 常见上限 998。 */
  private static String sanitizeHeader(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.replace("\r", "").replace("\n", "").trim();
    return cleaned.length() > 998 ? cleaned.substring(0, 998) : cleaned;
  }

  private boolean isProductionProfile() {
    if (environment == null) {
      return false;
    }
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
  }

  private MimeMessage buildMimeMessage(
      MailConfig mailConfig, DispatchCommand command, String externalRequestId) throws Exception {
    Properties props = new Properties();
    props.put("mail.smtp.host", mailConfig.host());
    props.put("mail.smtp.port", String.valueOf(mailConfig.port()));
    props.put("mail.smtp.auth", "true");
    // S-1.7：避免 MIME 长参数被 jakarta.mail 拆行后编码歧义
    props.put("mail.mime.splitlongparameters", "false");
    if (mailConfig.startTls()) {
      props.put("mail.smtp.starttls.enable", "true");
      // S-1.7：prod profile 要求 STARTTLS 成功，否则拒发（不降级为明文）
      if (isProductionProfile()) {
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
      }
    }
    Session session = Session.getInstance(props, null);

    Map<String, Object> channelConfig = command.channelConfig();
    String subject = sanitizeHeader(stringProp(channelConfig, "mail_subject"));
    if (!Texts.hasText(subject)) {
      subject = "File dispatch " + externalRequestId;
    }

    // S-1.7：from / to 走 header sanitize，阻止 "victim@evil\r\nBcc: attacker@..." 注入
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(sanitizeHeader(mailConfig.from())));
    message.setRecipients(
        Message.RecipientType.TO,
        InternetAddress.parse(sanitizeHeader(mailConfig.to().replace(";", ","))));
    message.setSubject(subject, StandardCharsets.UTF_8.name());

    MimeBodyPart textPart = new MimeBodyPart();
    textPart.setText(
        "Dispatch traceId=" + command.traceId() + " externalRequestId=" + externalRequestId,
        StandardCharsets.UTF_8.name());

    MimeMultipart multipart = new MimeMultipart();
    multipart.addBodyPart(textPart);
    message.setContent(multipart);
    return message;
  }

  /**
   * ⚠4 (2026-05-03): 流式读 attachment 到 bounded byte[]. 边读边检 cap 防 OOM, 不再用 Files.copy 落 temp.
   * MAX_ATTACHMENT_BYTES (25MB) cap 撑得住 byte[] 中转, 几个并发 SMTP 也不到 200MB 堆.
   */
  private byte[] readBoundedAttachment(Map<String, Object> fileRecord) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (InputStream in = fileContentResolver.openInputStream(fileRecord)) {
      byte[] chunk = new byte[8192];
      long total = 0;
      int n;
      while ((n = in.read(chunk)) > 0) {
        total += n;
        if (total > MAX_ATTACHMENT_BYTES) {
          throw new IllegalStateException(
              "mail attachment size exceeds cap " + MAX_ATTACHMENT_BYTES + " bytes");
        }
        buffer.write(chunk, 0, n);
      }
    }
    return buffer.toByteArray();
  }

  private void addAttachment(
      MimeMessage message, byte[] attachmentBytes, Map<String, Object> fileRecord)
      throws Exception {
    String attachName =
        firstNonBlank(
            String.valueOf(fileRecord.getOrDefault("original_file_name", "")),
            String.valueOf(fileRecord.getOrDefault("file_name", "attachment.bin")));
    String mimeType =
        String.valueOf(fileRecord.getOrDefault("mime_type", "application/octet-stream"));

    MimeBodyPart attachPart = new MimeBodyPart();
    ByteArrayDataSource dataSource = new ByteArrayDataSource(attachmentBytes, mimeType);
    dataSource.setName(attachName);
    attachPart.setDataHandler(new DataHandler(dataSource));
    attachPart.setFileName(attachName);

    MimeMultipart multipart = (MimeMultipart) message.getContent();
    multipart.addBodyPart(attachPart);
    message.setContent(multipart);
  }

  private void sendMail(MailConfig mailConfig, MimeMessage message) throws Exception {
    try (Transport transport = message.getSession().getTransport("smtp")) {
      transport.connect(
          mailConfig.host(), mailConfig.port(), mailConfig.smtpUser(), mailConfig.smtpPass());
      transport.sendMessage(message, message.getAllRecipients());
    }
  }

  private static String stringProp(Map<String, Object> map, String key) {
    Object v = map == null ? null : map.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private static int intProp(Map<String, Object> map, String key, int def) {
    Object v = map == null ? null : map.get(key);
    if (v instanceof Number n) {
      return n.intValue();
    }
    if (v != null && Texts.hasText(String.valueOf(v))) {
      return Integer.parseInt(String.valueOf(v).trim());
    }
    return def;
  }

  private static boolean boolProp(Map<String, Object> map, String key, boolean def) {
    Object v = map == null ? null : map.get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    if (v != null) {
      return Boolean.parseBoolean(String.valueOf(v).trim());
    }
    return def;
  }

  private static String firstNonBlank(String a, String b) {
    if (Texts.hasText(a)) {
      return a.trim();
    }
    if (Texts.hasText(b)) {
      return b.trim();
    }
    return "";
  }
}
