package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dispatch 出站 sidecar manifest 生成工具。默认后缀 .chk，内容为 JSON。 */
final class DispatchManifestSupport {

  static final String CHECKSUM_TYPE = "SHA-256";
  static final String CONTENT_TYPE = "application/json; charset=utf-8";
  private static final String DEFAULT_SUFFIX = ".chk";
  private static final HexFormat HEX = HexFormat.of();

  private DispatchManifestSupport() {}

  static boolean enabled(Map<String, Object> channelConfig) {
    Object raw = channelConfig == null ? null : channelConfig.get("dispatch_manifest_enabled");
    return raw == null || !"false".equalsIgnoreCase(String.valueOf(raw).trim());
  }

  static String suffix(Map<String, Object> channelConfig) {
    Object raw = channelConfig == null ? null : channelConfig.get("dispatch_manifest_suffix");
    String value = raw == null ? null : String.valueOf(raw).trim();
    return Texts.hasText(value) ? value : DEFAULT_SUFFIX;
  }

  static DigestingInputStream digesting(InputStream in) {
    return new DigestingInputStream(in, digest());
  }

  static PayloadDigest digest(byte[] payload) {
    MessageDigest digest = digest();
    digest.update(payload);
    return new PayloadDigest(payload.length, HEX.formatHex(digest.digest()));
  }

  static ManifestPayload manifestPayload(
      DispatchCommand command,
      String targetRef,
      String fileName,
      String externalRequestId,
      String receiptCode,
      PayloadDigest payloadDigest,
      String manifestRef) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", "dispatch-sidecar-manifest-v1");
    body.put("generatedAt", BatchDateTimeSupport.utcNow().toString());
    body.put("tenantId", command.tenantId());
    body.put("traceId", command.traceId());
    body.put("fileId", command.fileRecord().get("id"));
    body.put("fileName", fileName);
    body.put("targetRef", targetRef);
    body.put("sizeBytes", payloadDigest.sizeBytes());
    body.put("checksumType", CHECKSUM_TYPE);
    body.put("checksumValue", payloadDigest.sha256());
    body.put("sourceChecksumType", text(command.fileRecord().get("checksum_type")));
    body.put("sourceChecksumValue", text(command.fileRecord().get("checksum_value")));
    body.put("externalRequestId", externalRequestId);
    body.put("receiptCode", receiptCode);
    body.put("bizDate", command.fileRecord().get("biz_date"));
    body.put("channelCode", command.payload().channelCode());
    body.put("manifestRef", manifestRef);
    byte[] bytes = JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8);
    PayloadDigest manifestDigest = digest(bytes);
    return new ManifestPayload(
        manifestRef, manifestDigest.sha256(), manifestDigest.sizeBytes(), bytes);
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance(CHECKSUM_TYPE);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest unavailable", e);
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  record PayloadDigest(long sizeBytes, String sha256) {}

  record ManifestPayload(String ref, String checksum, long sizeBytes, byte[] bytes) {
    DispatchManifestRef toRef() {
      return new DispatchManifestRef(ref, checksum, sizeBytes);
    }
  }

  static final class DigestingInputStream extends FilterInputStream {
    private final DigestInputStream delegate;
    private long count;

    private DigestingInputStream(InputStream in, MessageDigest digest) {
      super(new DigestInputStream(in, digest));
      this.delegate = (DigestInputStream) this.in;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        count++;
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int read = super.read(b, off, len);
      if (read > 0) {
        count += read;
      }
      return read;
    }

    PayloadDigest finish() {
      return new PayloadDigest(count, HEX.formatHex(delegate.getMessageDigest().digest()));
    }
  }
}
