package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 将分发命令写入文件系统 outbox 目录（LOCAL 渠道及存根远程渠道）。 存根渠道符合设计意图：持久化载荷供运维核查，但不执行真实的 NAS/OSS/SFTP/EMAIL 传输协议。
 */
final class LocalOutboxDispatchSupport {

  private static final String LOCAL_SANDBOX_ROOT_PROP = "batch.dispatch.local-sandbox-root";
  private static final String DEFAULT_CHANNEL_CODE = "channel";

  private LocalOutboxDispatchSupport() {}

  static DispatchResult writeFilesystemEnvelope(
      DispatchCommand command, boolean transportStub, String stubDetail) {
    try {
      Map<String, Object> channelConfig = command.channelConfig();
      String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "NONE"));
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

      String endpoint =
          channelConfig.get("target_endpoint") == null
              ? null
              : String.valueOf(channelConfig.get("target_endpoint"));
      if (endpoint == null || endpoint.isBlank()) {
        endpoint = System.getProperty("java.io.tmpdir") + "/batch-dispatch-outbox";
      }
      Path directory = resolveLocalDirectory(endpoint);
      String channelCode =
          sanitizeFileSegment(
              String.valueOf(channelConfig.getOrDefault("channel_code", DEFAULT_CHANNEL_CODE)));
      Path envelopePath = directory.resolve(channelCode + "-" + externalRequestId + ".json");

      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("tenantId", command.tenantId());
      envelope.put("traceId", command.traceId());
      envelope.put("dispatchedAt", BatchDateTimeSupport.utcNow().toString());
      envelope.put("channelType", channelConfig.get("channel_type"));
      envelope.put("dispatchTarget", command.payload().dispatchTarget());
      envelope.put("externalRequestId", externalRequestId);
      envelope.put("receiptCode", receiptCode);
      envelope.put("acknowledged", acknowledged);
      envelope.put("receiptPending", pending);
      envelope.put("fileRecord", command.fileRecord());
      envelope.put("payload", command.payload());
      if (transportStub) {
        envelope.put("transportStub", Boolean.TRUE);
        envelope.put("transportStubDetail", stubDetail == null ? "" : stubDetail);
      }
      byte[] envelopeBytes = JsonUtils.toJson(envelope).getBytes(StandardCharsets.UTF_8);
      Files.write(envelopePath, envelopeBytes);
      DispatchManifestSupport.ManifestPayload manifest = null;
      if (DispatchManifestSupport.enabled(channelConfig)) {
        Path manifestPath =
            directory.resolve(
                envelopePath.getFileName() + DispatchManifestSupport.suffix(channelConfig));
        manifest =
            DispatchManifestSupport.manifestPayload(
                command,
                envelopePath.toString(),
                envelopePath.getFileName().toString(),
                externalRequestId,
                receiptCode,
                DispatchManifestSupport.digest(envelopeBytes),
                manifestPath.toString());
        Files.write(manifestPath, manifest.bytes());
      }

      String message =
          transportStub
              ? "transport stub: filesystem outbox only — " + (stubDetail == null ? "" : stubDetail)
              : "dispatched via local filesystem outbox";
      return new DispatchResult(
          true,
          externalRequestId,
          receiptCode,
          acknowledged,
          pending,
          message,
          envelopePath.toString());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(LocalOutboxDispatchSupport.class, "catch:Exception", ex);

      return new DispatchResult(false, null, null, false, false, ex.getMessage(), null);
    }
  }

  private static Path resolveLocalDirectory(String endpoint) throws Exception {
    Path directory = Path.of(endpoint).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path realDirectory = directory.toRealPath();
    String sandboxRootRaw = System.getProperty(LOCAL_SANDBOX_ROOT_PROP);
    if (Texts.hasText(sandboxRootRaw)) {
      Path sandboxRoot = Path.of(sandboxRootRaw).toAbsolutePath().normalize().toRealPath();
      if (!realDirectory.startsWith(sandboxRoot)) {
        throw new SecurityException(
            "LOCAL dispatch target_endpoint escapes sandbox root: real="
                + realDirectory
                + ", sandboxRoot="
                + sandboxRoot);
      }
    }
    return realDirectory;
  }

  private static String sanitizeFileSegment(String raw) {
    if (!Texts.hasText(raw)) {
      return DEFAULT_CHANNEL_CODE;
    }
    String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? DEFAULT_CHANNEL_CODE : cleaned;
  }
}
