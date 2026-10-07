package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.OwnerOnlyFiles;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

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
          DispatchReceiptSupport.resolve(command, channelConfig, FileReceiptPolicy.NONE.code());
      String externalRequestId = receipt.externalRequestId();
      String receiptCode = receipt.receiptCode();

      String endpoint = channelConfig.get("target_endpoint") == null
          ? null
          : String.valueOf(channelConfig.get("target_endpoint"));
      boolean privateTarget = !Texts.hasText(endpoint) || isDefaultOutboxEndpoint(endpoint);
      Path directory;
      if (privateTarget) {
        directory = validateLocalDirectory(
            OwnerOnlyFiles.createDirectories(
                PrivateTempFiles.resolveUnderTempRoot("batch-dispatch-outbox")),
            properties);
      } else {
        directory = validateLocalDirectory(
            Files.createDirectories(Path.of(endpoint).toAbsolutePath().normalize()), properties);
      }
      String channelCode = sanitizeFileSegment(
          String.valueOf(channelConfig.getOrDefault("channel_code", DEFAULT_CHANNEL_CODE)));
      Path envelopePath = directory.resolve(channelCode + "-" + externalRequestId + ".json");

      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("tenantId", command.tenantId());
      envelope.put(PipelineRuntimeKeys.TRACE_ID, command.traceId());
      envelope.put("dispatchedAt", BatchDateTimeSupport.utcNow().toString());
      envelope.put("channelType", channelConfig.get("channel_type"));
      envelope.put(DispatchRuntimeKeys.DISPATCH_TARGET, command.payload().dispatchTarget());
      envelope.put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, externalRequestId);
      envelope.put(DispatchRuntimeKeys.RECEIPT_CODE, receiptCode);
      envelope.put("acknowledged", receipt.acknowledged());
      envelope.put("receiptPending", receipt.pending());
      envelope.put(PipelineRuntimeKeys.FILE_RECORD, command.fileRecord());
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

  private static Path validateLocalDirectory(Path directory, DispatchRuntimeProperties properties)
      throws Exception {
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

  private static boolean isDefaultOutboxEndpoint(String endpoint) {
    return PrivateTempFiles.resolveUnderTempRoot("batch-dispatch-outbox")
        .toString()
        .equals(endpoint);
  }

  private static void writeOutboxFile(Path path, byte[] bytes, boolean privateTarget)
      throws IOException {
    if (!privateTarget) {
      Files.write(path, bytes);
      return;
    }
    if (Files.notExists(path)) {
      OwnerOnlyFiles.createFile(path);
    }
    OwnerOnlyFiles.protectExisting(path, false);
    Files.write(
        path,
        bytes,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
        java.nio.file.LinkOption.NOFOLLOW_LINKS);
  }

  private static String sanitizeFileSegment(String raw) {
    if (!Texts.hasText(raw)) {
      return DEFAULT_CHANNEL_CODE;
    }
    String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? DEFAULT_CHANNEL_CODE : cleaned;
  }
}
