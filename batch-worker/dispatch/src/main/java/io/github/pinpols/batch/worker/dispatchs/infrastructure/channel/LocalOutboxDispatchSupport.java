package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
    boolean publicationAttempted = false;
    String externalRequestId = null;
    String receiptCode = null;
    try {
      Map<String, Object> channelConfig = command.channelConfig();
      DispatchReceiptSupport.Receipt receipt =
          DispatchReceiptSupport.resolve(command, channelConfig, FileReceiptPolicy.NONE.code());
      externalRequestId = receipt.externalRequestId();
      receiptCode = receipt.receiptCode();

      String endpoint = channelConfig.get("target_endpoint") == null
          ? null
          : String.valueOf(channelConfig.get("target_endpoint"));
      boolean privateTarget = !Texts.hasText(endpoint) || isDefaultOutboxEndpoint(endpoint);
      Path directory = prepareLocalDirectory(endpoint, privateTarget, properties);
      String configuredChannelCode =
          String.valueOf(channelConfig.getOrDefault("channel_code", DEFAULT_CHANNEL_CODE));
      String channelCode = sanitizeFileSegment(configuredChannelCode);
      Path envelopePath = directory.resolve(channelCode + "-" + externalRequestId + ".json");

      Map<String, Object> envelope =
          buildEnvelope(command, channelConfig, receipt, transportStub, stubDetail);
      String envelopeJson = JsonUtils.toJson(envelope);
      byte[] envelopeBytes = envelopeJson.getBytes(StandardCharsets.UTF_8);
      // 信封一旦开始写出，就可能已被目录消费者观察；后续失败不能证明没有副作用。
      publicationAttempted = true;
      writeOutboxFile(envelopePath, envelopeBytes, privateTarget);
      DispatchManifestSupport.ManifestPayload manifest = null;
      if (DispatchManifestSupport.enabled(channelConfig)) {
        Path manifestPath = directory.resolve(
            envelopePath.getFileName() + DispatchManifestSupport.suffix(channelConfig));
        String envelopePathText = envelopePath.toString();
        String envelopeFileName = envelopePath.getFileName().toString();
        DispatchManifestSupport.PayloadDigest envelopeDigest =
            DispatchManifestSupport.digest(envelopeBytes);
        String manifestPathText = manifestPath.toString();
        DispatchManifestSupport.ManifestTarget manifestTarget =
            new DispatchManifestSupport.ManifestTarget(
                envelopePathText, envelopeFileName, manifestPathText);
        manifest = DispatchManifestSupport.manifestPayload(
            command, manifestTarget, externalRequestId, receiptCode, envelopeDigest);
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
      String failureSummary = SwallowedExceptionLogger.summary(ex);
      if (publicationAttempted) {
        return DispatchResult.externalOutcomeUnknown(
            externalRequestId,
            receiptCode,
            TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN
                + ": local outbox publication may have completed: " + failureSummary);
      }
      return DispatchResult.failureWithoutReceipt(failureSummary);
    }
  }

  /** 先建立并核验目标目录，发布围栏只覆盖之后可能被消费者观察的文件写入。 */
  private static Path prepareLocalDirectory(
      String endpoint, boolean privateTarget, DispatchRuntimeProperties properties)
      throws Exception {
    Path directory;
    if (privateTarget) {
      Path privateOutboxRoot = PrivateTempFiles.resolveUnderTempRoot("batch-dispatch-outbox");
      Path privateOutboxDirectory = OwnerOnlyFiles.createDirectories(privateOutboxRoot);
      directory = validateLocalDirectory(privateOutboxDirectory, properties);
    } else {
      Path configuredEndpoint = Path.of(endpoint);
      Path absoluteEndpoint = configuredEndpoint.toAbsolutePath();
      Path normalizedEndpoint = absoluteEndpoint.normalize();
      Path endpointDirectory = Files.createDirectories(normalizedEndpoint);
      directory = validateLocalDirectory(endpointDirectory, properties);
    }
    return directory;
  }

  private static Map<String, Object> buildEnvelope(
      DispatchCommand command,
      Map<String, Object> channelConfig,
      DispatchReceiptSupport.Receipt receipt,
      boolean transportStub,
      String stubDetail) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("tenantId", command.tenantId());
    envelope.put(PipelineRuntimeKeys.TRACE_ID, command.traceId());
    Instant dispatchedAt = BatchDateTimeSupport.utcNow();
    String dispatchedAtText = dispatchedAt.toString();
    envelope.put("dispatchedAt", dispatchedAtText);
    envelope.put("channelType", channelConfig.get("channel_type"));
    envelope.put(DispatchRuntimeKeys.DISPATCH_TARGET, command.payload().dispatchTarget());
    envelope.put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, receipt.externalRequestId());
    envelope.put(DispatchRuntimeKeys.RECEIPT_CODE, receipt.receiptCode());
    envelope.put("acknowledged", receipt.acknowledged());
    envelope.put("receiptPending", receipt.pending());
    envelope.put(PipelineRuntimeKeys.FILE_RECORD, command.fileRecord());
    envelope.put("payload", command.payload());
    if (transportStub) {
      envelope.put("transportStub", Boolean.TRUE);
      envelope.put("transportStubDetail", stubDetail == null ? "" : stubDetail);
    }
    return envelope;
  }

  private static Path validateLocalDirectory(Path directory, DispatchRuntimeProperties properties)
      throws Exception {
    Path realDirectory = directory.toRealPath();
    String sandboxRootRaw = properties.getLocalSandboxRoot();
    if (Texts.hasText(sandboxRootRaw)) {
      Path configuredSandboxRoot = Path.of(sandboxRootRaw);
      Path absoluteSandboxRoot = configuredSandboxRoot.toAbsolutePath();
      Path normalizedSandboxRoot = absoluteSandboxRoot.normalize();
      Path sandboxRoot = normalizedSandboxRoot.toRealPath();
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
    Path defaultOutboxPath = PrivateTempFiles.resolveUnderTempRoot("batch-dispatch-outbox");
    String defaultOutboxEndpoint = defaultOutboxPath.toString();
    return defaultOutboxEndpoint.equals(endpoint);
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
        LinkOption.NOFOLLOW_LINKS);
  }

  private static String sanitizeFileSegment(String raw) {
    if (!Texts.hasText(raw)) {
      return DEFAULT_CHANNEL_CODE;
    }
    String trimmedValue = raw.trim();
    String cleaned = trimmedValue.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? DEFAULT_CHANNEL_CODE : cleaned;
  }
}
