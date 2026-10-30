package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import lombok.Builder;
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

  private static final int DEFAULT_SMTP_TIMEOUT_MILLIS = 30_000;

  private static final class SmtpOutcomeUnknownException extends Exception {

    private SmtpOutcomeUnknownException(Exception cause) {
      super(
          "SMTP delivery outcome is unknown because the connection failed while awaiting send confirmation",
          cause);
    }
  }

  /** JavaMail 标准属性名；生产装配与测试断言共用，避免字面量在多处漂移。 */
  public static final String MAIL_SMTP_HOST_KEY = "mail.smtp.host";

  public static final String MAIL_SMTP_PORT_KEY = "mail.smtp.port";
  public static final String MAIL_SMTP_AUTH_KEY = "mail.smtp.auth";
  public static final String MAIL_SMTP_CONNECT_TIMEOUT_KEY = "mail.smtp.connectiontimeout";
  public static final String MAIL_SMTP_READ_TIMEOUT_KEY = "mail.smtp.timeout";
  public static final String MAIL_SMTP_WRITE_TIMEOUT_KEY = "mail.smtp.writetimeout";
  public static final String MAIL_MIME_SPLIT_LONG_PARAMETERS_KEY = "mail.mime.splitlongparameters";
  public static final String MAIL_SMTP_STARTTLS_ENABLE_KEY = "mail.smtp.starttls.enable";
  public static final String MAIL_SMTP_STARTTLS_REQUIRED_KEY = "mail.smtp.starttls.required";
  public static final String MAIL_SMTP_CHECK_SERVER_IDENTITY_KEY =
      "mail.smtp.ssl.checkserveridentity";

  private final DispatchFileContentResolver fileContentResolver;
  private final Environment environment;

  @Override
  public boolean supports(String channelType) {
    return channelType != null && "EMAIL".equalsIgnoreCase(channelType);
  }

  @Builder
  private record MailConfig(
      String host,
      int port,
      String smtpUser,
      String smtpPass,
      boolean startTls,
      int connectTimeoutMillis,
      int readTimeoutMillis,
      int writeTimeoutMillis,
      String from,
      String to) {}

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();

    MailConfig mailConfig = resolveMailConfig(channelConfig);
    if (mailConfig == null) {
      String host = stringProp(channelConfig, "smtp_host");
      if (!Texts.hasText(host)) {
        return DispatchResult.failureWithoutReceipt("smtp_host missing");
      }
      return DispatchResult.failureWithoutReceipt("mail_from/mail_to or target_endpoint missing");
    }

    DispatchReceiptSupport.Receipt receipt =
        DispatchReceiptSupport.resolve(command, channelConfig, FileReceiptPolicy.SYNC.code());

    try {
      MimeMessage message = buildMimeMessage(mailConfig, command, receipt.externalRequestId());
      // ⚠4 (2026-05-03): 改 ByteArrayDataSource 直接桥接 InputStream → SMTP socket. 之前 Files.copy 落 temp
      // file
      // 后 FileDataSource 又被 jakarta.mail 整块读到 socket = 2× 磁盘 IO. 25MB 上限已防 OOM.
      byte[] attachmentBytes = readBoundedAttachment(command.fileRecord());
      addAttachment(message, attachmentBytes, command.fileRecord());
      sendMail(mailConfig, message);
      return new DispatchResult(
          true,
          receipt.externalRequestId(),
          receipt.receiptCode(),
          receipt.acknowledged(),
          receipt.pending(),
          "sent via SMTP",
          "mailto:" + mailConfig.to());
    } catch (SmtpOutcomeUnknownException ex) {
      SwallowedExceptionLogger.warn(
          SmtpEmailDispatchChannelAdapter.class, "smtp-outcome-unknown", ex);
      return DispatchResult.externalOutcomeUnknown(
          receipt.externalRequestId(),
          receipt.receiptCode(),
          "EXTERNAL_OUTCOME_UNKNOWN: " + SwallowedExceptionLogger.summary(ex));
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(SmtpEmailDispatchChannelAdapter.class, "catch:Exception", ex);

      return new DispatchResult(
          false,
          receipt.externalRequestId(),
          receipt.receiptCode(),
          false,
          false,
          SwallowedExceptionLogger.summary(ex),
          null);
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
    int connectTimeoutMillis = positiveIntProp(
        channelConfig, "smtp_connection_timeout_millis", DEFAULT_SMTP_TIMEOUT_MILLIS);
    int readTimeoutMillis =
        positiveIntProp(channelConfig, "smtp_timeout_millis", DEFAULT_SMTP_TIMEOUT_MILLIS);
    int writeTimeoutMillis =
        positiveIntProp(channelConfig, "smtp_write_timeout_millis", DEFAULT_SMTP_TIMEOUT_MILLIS);
    // S-1.7：prod profile 强制 STARTTLS，覆盖渠道"关掉 TLS"的配置；dev/local 允许关闭
    if (isProductionProfile()) {
      startTls = true;
    }

    String configuredMailFrom = stringProp(channelConfig, "mail_from");
    String from = firstNonBlank(configuredMailFrom, smtpUser);
    String configuredMailTo = stringProp(channelConfig, "mail_to");
    String configuredTargetEndpoint = stringProp(channelConfig, "target_endpoint");
    String to = firstNonBlank(configuredMailTo, configuredTargetEndpoint);
    if (!Texts.hasText(from) || !Texts.hasText(to)) {
      return null;
    }
    return MailConfig.builder()
        .host(host)
        .port(port)
        .smtpUser(smtpUser)
        .smtpPass(smtpPass)
        .startTls(startTls)
        .connectTimeoutMillis(connectTimeoutMillis)
        .readTimeoutMillis(readTimeoutMillis)
        .writeTimeoutMillis(writeTimeoutMillis)
        .from(from)
        .to(to)
        .build();
  }

  /** S-1.7：剥离 CR/LF 防 header 注入；长度截断到 RFC 5322 header 常见上限 998。 */
  private static String sanitizeHeader(String raw) {
    if (raw == null) {
      return null;
    }
    String carriageReturnsRemoved = raw.replace("\r", "");
    String lineFeedsRemoved = carriageReturnsRemoved.replace("\n", "");
    String cleaned = lineFeedsRemoved.trim();
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
      MailConfig mailConfig, DispatchCommand command, String externalRequestId)
      throws MessagingException {
    Properties props = new Properties();
    props.put(MAIL_SMTP_HOST_KEY, mailConfig.host());
    props.put(MAIL_SMTP_PORT_KEY, String.valueOf(mailConfig.port()));
    props.put(MAIL_SMTP_AUTH_KEY, "true");
    props.put(MAIL_SMTP_CONNECT_TIMEOUT_KEY, String.valueOf(mailConfig.connectTimeoutMillis()));
    props.put(MAIL_SMTP_READ_TIMEOUT_KEY, String.valueOf(mailConfig.readTimeoutMillis()));
    props.put(MAIL_SMTP_WRITE_TIMEOUT_KEY, String.valueOf(mailConfig.writeTimeoutMillis()));
    // S-1.7：避免 MIME 长参数被 jakarta.mail 拆行后编码歧义
    props.put(MAIL_MIME_SPLIT_LONG_PARAMETERS_KEY, "false");
    if (mailConfig.startTls()) {
      props.put(MAIL_SMTP_STARTTLS_ENABLE_KEY, "true");
      // S-1.7：prod profile 要求 STARTTLS 成功，否则拒发（不降级为明文）
      if (isProductionProfile()) {
        props.put(MAIL_SMTP_STARTTLS_REQUIRED_KEY, "true");
        props.put(MAIL_SMTP_CHECK_SERVER_IDENTITY_KEY, "true");
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
    String sanitizedFrom = sanitizeHeader(mailConfig.from());
    InternetAddress fromAddress = new InternetAddress(sanitizedFrom);
    message.setFrom(fromAddress);
    String normalizedRecipients = mailConfig.to().replace(";", ",");
    String sanitizedRecipients = sanitizeHeader(normalizedRecipients);
    InternetAddress[] recipients = InternetAddress.parse(sanitizedRecipients);
    message.setRecipients(Message.RecipientType.TO, recipients);
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
  private byte[] readBoundedAttachment(Map<String, Object> fileRecord) throws IOException {
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
      throws MessagingException, IOException {
    String originalFileName = String.valueOf(fileRecord.getOrDefault("original_file_name", ""));
    String fileName = String.valueOf(fileRecord.getOrDefault("file_name", "attachment.bin"));
    String attachName = firstNonBlank(originalFileName, fileName);
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

  private void sendMail(MailConfig mailConfig, MimeMessage message)
      throws MessagingException, SmtpOutcomeUnknownException {
    Transport transport = message.getSession().getTransport("smtp");
    try {
      transport.connect(
          mailConfig.host(), mailConfig.port(), mailConfig.smtpUser(), mailConfig.smtpPass());
      try {
        transport.sendMessage(message, message.getAllRecipients());
      } catch (MessagingException | RuntimeException exception) {
        throw new SmtpOutcomeUnknownException(exception);
      }
    } finally {
      try {
        transport.close();
      } catch (MessagingException exception) {
        // 将 Worker 选定的系统 Pipeline 契约固定到任务中，以便确定性重放。
        SwallowedExceptionLogger.warn(
            SmtpEmailDispatchChannelAdapter.class, "smtp-transport-close-failed", exception);
      }
    }
  }

  private static String stringProp(Map<String, Object> map, String key) {
    return DispatchChannelConfigSupport.stringProp(map, key);
  }

  private static int intProp(Map<String, Object> map, String key, int def) {
    return DispatchChannelConfigSupport.intProp(map, key, def);
  }

  private static int positiveIntProp(Map<String, Object> map, String key, int def) {
    int value = intProp(map, key, def);
    return value > 0 ? value : def;
  }

  private static boolean boolProp(Map<String, Object> map, String key, boolean def) {
    Object v = map == null ? null : map.get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    if (v != null) {
      String propertyText = String.valueOf(v);
      String trimmedPropertyText = propertyText.trim();
      return Boolean.parseBoolean(trimmedPropertyText);
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
