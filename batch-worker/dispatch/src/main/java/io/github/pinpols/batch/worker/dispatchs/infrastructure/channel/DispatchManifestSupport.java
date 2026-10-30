package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dispatch 出站 sidecar manifest 生成工具。默认后缀 .chk，内容为 JSON。 */
final class DispatchManifestSupport {

  static final String CHECKSUM_TYPE = CryptoAlgorithms.SHA_256;
  static final String CONTENT_TYPE = "application/json; charset=utf-8";
  private static final String DEFAULT_SUFFIX = ".chk";
  private static final HexFormat HEX = HexFormat.of();

  private DispatchManifestSupport() {}

  static boolean enabled(Map<String, Object> channelConfig) {
    Object raw = channelConfig == null ? null : channelConfig.get("dispatch_manifest_enabled");
    if (EmptyChecks.isNull(raw)) {
      return true;
    }
    String rawValue = String.valueOf(raw);
    String normalizedValue = rawValue.trim();
    return !"false".equalsIgnoreCase(normalizedValue);
  }

  static String suffix(Map<String, Object> channelConfig) {
    Object raw = channelConfig == null ? null : channelConfig.get("dispatch_manifest_suffix");
    String value = null;
    if (EmptyChecks.isNotNull(raw)) {
      String rawValue = String.valueOf(raw);
      value = rawValue.trim();
    }
    return Texts.hasText(value) ? value : DEFAULT_SUFFIX;
  }

  static DigestingInputStream digesting(InputStream in) {
    MessageDigest messageDigest = digest();
    return new DigestingInputStream(in, messageDigest);
  }

  static PayloadDigest digest(byte[] payload) {
    MessageDigest digest = digest();
    digest.update(payload);
    byte[] digestBytes = digest.digest();
    String digestHex = HEX.formatHex(digestBytes);
    return new PayloadDigest(payload.length, digestHex);
  }

  static ManifestPayload manifestPayload(
      DispatchCommand command,
      ManifestTarget target,
      String externalRequestId,
      String receiptCode,
      PayloadDigest payloadDigest) {
    String targetRef = target.targetRef();
    String fileName = target.fileName();
    String manifestRef = target.manifestRef();
    Map<String, Object> fileRecord = command.fileRecord() == null ? Map.of() : command.fileRecord();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", "dispatch-sidecar-manifest-v1");
    Instant generatedAt = BatchDateTimeSupport.utcNow();
    String generatedAtText = generatedAt.toString();
    body.put("generatedAt", generatedAtText);
    body.put("tenantId", command.tenantId());
    body.put(PipelineRuntimeKeys.TRACE_ID, command.traceId());
    body.put(PipelineRuntimeKeys.FILE_ID, fileRecord.get("id"));
    body.put(PipelineRuntimeKeys.FILE_NAME, fileName);
    body.put("targetRef", targetRef);
    body.put("sizeBytes", payloadDigest.sizeBytes());
    body.put(PipelineRuntimeKeys.CHECKSUM_TYPE, CHECKSUM_TYPE);
    body.put(PipelineRuntimeKeys.CHECKSUM_VALUE, payloadDigest.sha256());
    Object sourceChecksumTypeValue = fileRecord.get("checksum_type");
    String sourceChecksumType = text(sourceChecksumTypeValue);
    body.put("sourceChecksumType", sourceChecksumType);
    Object sourceChecksumValue = fileRecord.get("checksum_value");
    String sourceChecksum = text(sourceChecksumValue);
    body.put("sourceChecksumValue", sourceChecksum);
    body.put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, externalRequestId);
    body.put(DispatchRuntimeKeys.RECEIPT_CODE, receiptCode);
    Object bizDate = fileRecord.get("biz_date");
    body.put(PipelineRuntimeKeys.BIZ_DATE, text(bizDate));
    body.put(
        DispatchRuntimeKeys.CHANNEL_CODE,
        EmptyChecks.isNull(command.payload()) ? null : command.payload().channelCode());
    body.put("manifestRef", manifestRef);
    String serializedBody = JsonUtils.toJson(body);
    byte[] bytes = serializedBody.getBytes(StandardCharsets.UTF_8);
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
    String rawText = String.valueOf(value);
    String text = rawText.trim();
    return text.isEmpty() ? null : text;
  }

  /** 只聚合本次产物与其旁挂清单的位置，不负责重新推导回执或生成时刻。 */
  record ManifestTarget(String targetRef, String fileName, String manifestRef) {}

  record PayloadDigest(long sizeBytes, String sha256) {}

  @SuppressWarnings("java:S6218")
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
      byte[] digestBytes = delegate.getMessageDigest().digest();
      String digestHex = HEX.formatHex(digestBytes);
      return new PayloadDigest(count, digestHex);
    }
  }
}
