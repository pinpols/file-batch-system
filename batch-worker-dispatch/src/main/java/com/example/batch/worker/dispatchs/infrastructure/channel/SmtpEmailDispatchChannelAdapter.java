package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SMTP email with file attachment from {@code file_record}.
 */
@Component
@RequiredArgsConstructor
public class SmtpEmailDispatchChannelAdapter implements DispatchChannelAdapter {

    private final DispatchFileContentResolver fileContentResolver;

    @Override
    public boolean supports(String channelType) {
        return channelType != null && "EMAIL".equalsIgnoreCase(channelType);
    }

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        Map<String, Object> channelConfig = command.channelConfig();
        String host = stringProp(channelConfig, "smtp_host");
        if (!StringUtils.hasText(host)) {
            return new DispatchResult(false, null, null, false, false, "smtp_host missing", null);
        }
        int port = intProp(channelConfig, "smtp_port", 587);
        String smtpUser = stringProp(channelConfig, "smtp_username");
        String smtpPass = stringProp(channelConfig, "smtp_password");
        boolean startTls = boolProp(channelConfig, "smtp_starttls", true);

        String from = firstNonBlank(stringProp(channelConfig, "mail_from"), smtpUser);
        String to = firstNonBlank(stringProp(channelConfig, "mail_to"), stringProp(channelConfig, "target_endpoint"));
        if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            return new DispatchResult(false, null, null, false, false, "mail_from/mail_to or target_endpoint missing", null);
        }

        String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
        String externalRequestId = command.payload().externalRequestId() != null && !command.payload().externalRequestId().isBlank()
                ? command.payload().externalRequestId()
                : UUID.randomUUID().toString();
        String receiptCode = command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()
                ? command.payload().receiptCode()
                : "R-" + externalRequestId;
        boolean acknowledged = "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
        boolean pending = "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);

        Map<String, Object> fileRecord = command.fileRecord();
        String attachName = firstNonBlank(
                String.valueOf(fileRecord.getOrDefault("original_file_name", "")),
                String.valueOf(fileRecord.getOrDefault("file_name", "attachment.bin"))
        );
        String subject = stringProp(channelConfig, "mail_subject");
        if (!StringUtils.hasText(subject)) {
            subject = "File dispatch " + externalRequestId;
        }
        String mimeType = String.valueOf(fileRecord.getOrDefault("mime_type", "application/octet-stream"));

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        if (startTls) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        Session session = Session.getInstance(props, null);
        Path attachmentFile = null;
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to.replace(";", ",")));
            message.setSubject(subject, StandardCharsets.UTF_8.name());

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText("Dispatch traceId=" + command.traceId() + " externalRequestId=" + externalRequestId, StandardCharsets.UTF_8.name());

            MimeBodyPart attachPart = new MimeBodyPart();
            attachmentFile = Files.createTempFile("dispatch-mail-", "-" + sanitizeAttachmentSuffix(attachName));
            try (InputStream in = fileContentResolver.openInputStream(fileRecord)) {
                Files.copy(in, attachmentFile, StandardCopyOption.REPLACE_EXISTING);
            }
            FileDataSource dataSource = new FileDataSource(attachmentFile.toFile());
            dataSource.setFileTypeMap(new SingleMimeTypeFileTypeMap(mimeType));
            attachPart.setDataHandler(new DataHandler(dataSource));
            attachPart.setFileName(attachName);

            MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(attachPart);
            message.setContent(multipart);

            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(host, port, smtpUser, smtpPass);
                transport.sendMessage(message, message.getAllRecipients());
            }
            return new DispatchResult(true, externalRequestId, receiptCode, acknowledged, pending, "sent via SMTP", "mailto:" + to);
        } catch (Exception ex) {
            return new DispatchResult(false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
        } finally {
            deleteQuietly(attachmentFile);
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
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
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
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        if (StringUtils.hasText(b)) {
            return b.trim();
        }
        return "";
    }

    private static String sanitizeAttachmentSuffix(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "attachment.bin";
        }
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static final class SingleMimeTypeFileTypeMap extends jakarta.activation.FileTypeMap {

        private final String mimeType;

        private SingleMimeTypeFileTypeMap(String mimeType) {
            this.mimeType = StringUtils.hasText(mimeType) ? mimeType : "application/octet-stream";
        }

        @Override
        public String getContentType(java.io.File file) {
            return mimeType;
        }

        @Override
        public String getContentType(String filename) {
            return mimeType;
        }
    }
}
