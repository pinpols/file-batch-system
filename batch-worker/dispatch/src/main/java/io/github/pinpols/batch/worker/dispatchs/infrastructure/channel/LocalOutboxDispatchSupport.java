package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 将分发命令写入文件系统 outbox 目录（LOCAL 渠道及存根远程渠道）。 存根渠道符合设计意图：持久化载荷供运维核查，但不执行真实的 NAS/OSS/SFTP/EMAIL 传输协议。
 */
final class LocalOutboxDispatchSupport {

  private static final String DEFAULT_CHANNEL_CODE = "channel";
  private static final DispatchRuntimeProperties DEFAULT_RUNTIME_PROPERTIES =
      new DispatchRuntimeProperties();

  private LocalOutboxDispatchSupport() {}

  static DispatchResult writeFilesystemEnvelope(
      DispatchCommand command, boolean transportStub, String stubDetail) {
    return writeFilesystemEnvelope(command, transportStub, stubDetail, DEFAULT_RUNTIME_PROPERTIES);
  }

  static DispatchResult writeFilesystemEnvelope(
      DispatchCommand command,
      boolean transportStub,
      String stubDetail,
      DispatchRuntimeProperties properties) {
    try {
      Map<String, Object> channelConfig = command.channelConfig();
      DispatchReceiptSupport.Receipt receipt =
          DispatchReceiptSupport.resolve(command, channelConfig, "NONE");
      String externalRequestId = receipt.externalRequestId();
      String receiptCode = receipt.receiptCode();

      String endpoint = channelConfig.get("target_endpoint") == null
          ? null
          : String.valueOf(channelConfig.get("target_endpoint"));
      if (endpoint == null || endpoint.isBlank()) {
        endpoint = System.getProperty("java.io.tmpdir") + "/batch-dispatch-outbox";
      }
      Path directory = resolveLocalDirectory(endpoint, properties);
      boolean privateTarget = isDefaultOutboxEndpoint(endpoint);
      if (privateTarget) {
        hardenPrivateDirectory(directory);
      }
      String channelCode = sanitizeFileSegment(
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
      envelope.put("acknowledged", receipt.acknowledged());
      envelope.put("receiptPending", receipt.pending());
      envelope.put("fileRecord", command.fileRecord());
      envelope.put("payload", command.payload());
      if (transportStub) {
        envelope.put("transportStub", Boolean.TRUE);
        envelope.put("transportStubDetail", stubDetail == null ? "" : stubDetail);
      }
      byte[] envelopeBytes = JsonUtils.toJson(envelope).getBytes(StandardCharsets.UTF_8);
      writeOutboxFile(envelopePath, envelopeBytes, privateTarget);
      DispatchManifestSupport.ManifestPayload manifest = null;
      if (DispatchManifestSupport.enabled(channelConfig)) {
        Path manifestPath = directory.resolve(
            envelopePath.getFileName() + DispatchManifestSupport.suffix(channelConfig));
        manifest = DispatchManifestSupport.manifestPayload(
            command,
            envelopePath.toString(),
            envelopePath.getFileName().toString(),
            externalRequestId,
            receiptCode,
            DispatchManifestSupport.digest(envelopeBytes),
            manifestPath.toString());
        writeOutboxFile(manifestPath, manifest.bytes(), privateTarget);
      }

      String message = transportStub
          ? "transport stub: filesystem outbox only — " + (stubDetail == null ? "" : stubDetail)
          : "dispatched via local filesystem outbox";
      return new DispatchResult(
          true,
          externalRequestId,
          receiptCode,
          receipt.acknowledged(),
          receipt.pending(),
          message,
          envelopePath.toString(),
          manifest == null ? null : manifest.toRef());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(LocalOutboxDispatchSupport.class, "catch:Exception", ex);

      return new DispatchResult(false, null, null, false, false, ex.getMessage(), null);
    }
  }

  private static Path resolveLocalDirectory(String endpoint, DispatchRuntimeProperties properties)
      throws Exception {
    Path directory = Path.of(endpoint).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path realDirectory = directory.toRealPath();
    String sandboxRootRaw = properties.getLocalSandboxRoot();
    if (Texts.hasText(sandboxRootRaw)) {
      Path sandboxRoot = Path.of(sandboxRootRaw).toAbsolutePath().normalize().toRealPath();
      if (!realDirectory.startsWith(sandboxRoot)) {
        throw new SecurityException("LOCAL dispatch target_endpoint escapes sandbox root: real="
            + realDirectory
            + ", sandboxRoot="
            + sandboxRoot);
      }
    }
    return realDirectory;
  }

  private static void hardenPrivateDirectory(Path directory) {
    try {
      Files.setPosixFilePermissions(
          directory,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException | IOException ignored) {
      // The default directory is still isolated by the host filesystem on non-POSIX platforms.
    }
  }

  private static boolean isDefaultOutboxEndpoint(String endpoint) {
    return (System.getProperty("java.io.tmpdir") + "/batch-dispatch-outbox").equals(endpoint);
  }

  private static void writeOutboxFile(Path path, byte[] bytes, boolean privateTarget)
      throws IOException {
    if (!privateTarget) {
      Files.write(path, bytes);
      return;
    }
    if (Files.notExists(path)) {
      try {
        Files.createFile(
            path,
            PosixFilePermissions.asFileAttribute(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
      } catch (UnsupportedOperationException ignored) {
        Files.createFile(path);
      }
    }
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Windows ACLs are managed by the host.
    }
    Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  private static String sanitizeFileSegment(String raw) {
    if (!Texts.hasText(raw)) {
      return DEFAULT_CHANNEL_CODE;
    }
    String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? DEFAULT_CHANNEL_CODE : cleaned;
  }
}
