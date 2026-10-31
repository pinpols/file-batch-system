package io.github.pinpols.batch.worker.exports.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.FileChecksumType;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.infrastructure.S3ExportStorage;
import io.github.pinpols.batch.worker.exports.stage.format.ExportFormatStrategy;
import io.github.pinpols.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 导出存储阶段：将生成的临时文件上传至对象存储（先写 .part 再 copy 提升），并完成 SHA-256 校验。 */
@Component
public class StoreStep implements ExportStageStep {

  private static final String KEY_DOWNLOAD_REQUIRES_APPROVAL = "download_requires_approval";
  private static final ObjectMapper ERROR_OBJECT_MAPPER = JsonUtils.newDefaultMapper();
  private static final String ERROR_CODE_STORE_INVALID = "EXPORT_STORE_INVALID";

  private final S3ExportStorage s3ExportStorage;
  private final BatchObjectCryptoService cryptoService;
  private final ExportFormatStrategyRegistry formatStrategyRegistry;
  private final PlatformFileRecordRepository fileRecords;
  private final S3StorageProperties storageProperties;

  @Autowired
  public StoreStep(
      S3ExportStorage s3ExportStorage,
      BatchObjectCryptoService cryptoService,
      ExportFormatStrategyRegistry formatStrategyRegistry,
      PlatformFileRecordRepository fileRecords,
      S3StorageProperties storageProperties) {
    this.s3ExportStorage = s3ExportStorage;
    this.cryptoService = cryptoService;
    this.formatStrategyRegistry = formatStrategyRegistry;
    this.fileRecords = fileRecords;
    this.storageProperties = storageProperties;
  }

  public StoreStep(S3ExportStorage s3ExportStorage, BatchObjectCryptoService cryptoService) {
    this(s3ExportStorage, cryptoService, null, null, null);
  }

  public StoreStep(
      S3ExportStorage s3ExportStorage,
      BatchObjectCryptoService cryptoService,
      ExportFormatStrategyRegistry formatStrategyRegistry) {
    this(s3ExportStorage, cryptoService, formatStrategyRegistry, null, null);
  }

  @Override
  public ExportStage stage() {
    return ExportStage.STORE;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    // ADR-026: 演练模式下不上传对象存储/SFTP，仅落 SHA + 占位 objectName 让下游 stage 链路完整跑完
    Map<String, Object> dryRunAttributes = attributes(context);
    boolean dryRun = DryRunGuard.fromAttributes(dryRunAttributes).isDryRun();
    if (dryRun) {
      return executeDryRun(context);
    }
    Object generatedFilePath = attribute(context, PipelineRuntimeKeys.GENERATED_FILE_PATH);
    if (!(generatedFilePath instanceof String pathText) || !Texts.hasText(pathText)) {
      return ExportStageResult.failure(
          stage(),
          ERROR_CODE_STORE_INVALID,
          "error.export.store.invalid",
          new Object[] {"export data missing"},
          "export data missing",
          ERROR_OBJECT_MAPPER);
    }
    EncryptionOutcome encryption = null;
    try {
      String objectName = resolveObjectName(context);
      String tempObjectName = resolveTempObjectName(context, objectName);
      String contentType = resolveContentType(context);
      Path generatedFile = Path.of(pathText);
      if (!Files.exists(generatedFile)) {
        return ExportStageResult.failure(
            stage(),
            ERROR_CODE_STORE_INVALID,
            "error.export.store.invalid",
            new Object[] {"generated file missing"},
            "generated file missing",
            ERROR_OBJECT_MAPPER);
      }

      ExportStageResult committed = reuseRegisteredArtifact(context, objectName, generatedFile);
      if (EmptyChecks.isNotNull(committed)) {
        return committed;
      }

      encryption = encryptIfNeeded(context, generatedFile);
      Path uploadPath = encryption.uploadPath();
      boolean encrypt = encryption.encrypted();

      String expectedSha = sha256Hex(uploadPath);
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.CHECKSUM_TYPE, FileChecksumType.SHA_256.code());
      context.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, expectedSha);

      String tempKey = s3ExportStorage.writeObject(
          tempObjectName,
          uploadPath,
          encrypt ? BatchFileConstants.CONTENT_TYPE_OCTET_STREAM : contentType);
      ExportStageResult partVerification = verifyPartUpload(expectedSha, tempKey);
      if (partVerification != null) {
        return partVerification;
      }

      ExportStageResult finalVerification = promoteAndVerifyFinal(expectedSha, tempKey, objectName);
      if (finalVerification != null) {
        return finalVerification;
      }

      return commitStoredObject(context, objectName, tempKey, generatedFile);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(StoreStep.class, "catch:Exception", ex);

      ExportStage failureStage = stage();
      String localizedMessageArgument = ex.getMessage();
      String fallbackMessage = ex.getMessage();
      return ExportStageResult.failure(
          failureStage,
          "EXPORT_STORE_FAILED",
          "error.export.store.failed",
          new Object[] {localizedMessageArgument},
          fallbackMessage,
          ERROR_OBJECT_MAPPER);
    } finally {
      if (EmptyChecks.isNotNull(encryption)) {
        closeEncryptedTempFile(encryption.temporaryFile());
      }
    }
  }

  /** 重领只复用同一 Job 已登记且校验通过的产物，禁止新运行丢弃本次生成内容并借用旧对象。 */
  private ExportStageResult reuseRegisteredArtifact(
      ExportJobContext context, String objectName, Path generatedFile) throws IOException {
    if (EmptyChecks.isNull(fileRecords) || EmptyChecks.isNull(storageProperties)) {
      return null;
    }
    Map<String, Object> record = fileRecords.loadFileRecordByStoragePath(
        context.getTenantId(), storageProperties.getBucket(), objectName);
    if (EmptyChecks.isEmpty(record)) {
      return null;
    }
    if (!ExportArtifactIdentity.belongsToJob(context, record)) {
      return artifactConflict("registered artifact belongs to another or unknown Job instance");
    }
    String recordedChecksum = text(record.get("checksum_value"));
    String checksumType = text(record.get("checksum_type"));
    if (!FileChecksumType.SHA_256.code().equalsIgnoreCase(checksumType)
        || !Texts.hasText(recordedChecksum)) {
      return artifactConflict("registered artifact has no SHA-256 checksum");
    }
    String storedChecksum = s3ExportStorage.sha256Hex(objectName);
    if (!recordedChecksum.equalsIgnoreCase(storedChecksum)) {
      return artifactConflict("registered artifact bytes do not match file_record checksum");
    }
    context.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_TYPE, checksumType);
    context.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, recordedChecksum);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_SIZE_BYTES, record.get("file_size_bytes"));
    Map<?, ?> metadata = ExportArtifactIdentity.metadata(record);
    for (String key : new String[] {
      "contentEncryptionEnabled",
      "encryptionKeyRef",
      "encryptionObjectVersion",
      KEY_DOWNLOAD_REQUIRES_APPROVAL
    }) {
      if (metadata.containsKey(key)) context.getAttributes().put(key, metadata.get(key));
    }
    context.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, objectName);
    context.getAttributes().put(PipelineRuntimeKeys.EXPORT_STORE_COMMITTED, Boolean.TRUE);
    Files.deleteIfExists(generatedFile);
    return ExportStageResult.success(stage());
  }

  private ExportStageResult artifactConflict(String reason) {
    return ExportStageResult.failure(
        stage(),
        "EXPORT_STORE_ARTIFACT_CONFLICT",
        "error.export.store.artifact_conflict",
        new Object[] {reason},
        reason,
        ERROR_OBJECT_MAPPER);
  }

  private String text(Object value) {
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  private void closeEncryptedTempFile(PrivateTempFiles.LockedTempFile file) {
    if (EmptyChecks.isNull(file)) {
      return;
    }
    try {
      file.close();
    } catch (IOException ex) {
      SwallowedExceptionLogger.warn(StoreStep.class, "encrypted-temp-cleanup-failed", ex);
    }
  }

  private String resolveObjectName(ExportJobContext context) {
    // R2-P1-6：之前 fallback 用 UUID.randomUUID()——执行成功一半（已 PUT .part 但未 promote）后
    // worker 崩溃，重试 context attribute 重建为空，新 UUID → 旧 .part 永远孤儿。
    // 改为基于 jobInstanceId + taskId 的确定性名称：同一个 (instance, task) 重试得到相同 objectName，
    // 重试时直接覆盖旧 .part 文件，不留孤儿。jobInstanceId / taskId 缺失时回退 UUID 保留兼容。
    Object existing = context.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME);
    if (existing instanceof String s && Texts.hasText(s)) {
      return s;
    }
    Object jobInstanceId = context.getAttributes().get(PipelineRuntimeKeys.JOB_INSTANCE_ID);
    Object taskId = context.getAttributes().get(PipelineRuntimeKeys.TASK_ID);
    if (jobInstanceId != null && taskId != null) {
      return BatchFileConstants.EXPORT_OBJECT_PREFIX
          + "job-"
          + jobInstanceId
          + "/task-"
          + taskId
          + BatchFileConstants.JSON_SUFFIX;
    }
    return BatchFileConstants.EXPORT_OBJECT_PREFIX
        + UUID.randomUUID()
        + BatchFileConstants.JSON_SUFFIX;
  }

  private String resolveTempObjectName(ExportJobContext context, String objectName) {
    Object configuredTempObjectName =
        context.getAttributes().get(PipelineRuntimeKeys.TEMP_OBJECT_NAME);
    String defaultTempObjectName = objectName + BatchFileConstants.FILE_PART_SUFFIX;
    String tempObjectName = resolveText(configuredTempObjectName, defaultTempObjectName);
    if (!tempObjectName.endsWith(BatchFileConstants.FILE_PART_SUFFIX)) {
      tempObjectName = tempObjectName + BatchFileConstants.FILE_PART_SUFFIX;
    }
    return tempObjectName;
  }

  private String resolveContentType(ExportJobContext context) {
    String fileFormatType = String.valueOf(context
        .getAttributes()
        .getOrDefault(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, FileTemplateFormat.JSON.code()));
    if (EmptyChecks.isNull(formatStrategyRegistry)) {
      return ExportFormatStrategy.contentTypeFor(fileFormatType);
    }
    return formatStrategyRegistry.resolve(fileFormatType).contentType();
  }

  private EncryptionOutcome encryptIfNeeded(ExportJobContext context, Path generatedFile)
      throws IOException {
    Map<String, Object> security = templateSecurity(context);
    context
        .getAttributes()
        .put("downloadRequiresApproval", security.get(KEY_DOWNLOAD_REQUIRES_APPROVAL));
    boolean encrypt = cryptoService.shouldEncrypt(security);
    if (encrypt) {
      PrivateTempFiles.LockedTempFile temporaryFile =
          PrivateTempFiles.createLockedTempFile(BatchFileConstants.ENCRYPTED_EXPORT_PREFIX, ".bin");
      Path encryptedPath = temporaryFile.path();
      boolean completed = false;
      try {
        String keyRefForEncryption = cryptoService.resolveKeyRef(security);
        cryptoService.encrypt(generatedFile, encryptedPath, keyRefForEncryption);
        context.getAttributes().put("contentEncryptionEnabled", Boolean.TRUE);
        String keyRefForMetadata = cryptoService.resolveKeyRef(security);
        context.getAttributes().put("encryptionKeyRef", keyRefForMetadata);
        context.getAttributes().put("encryptionObjectVersion", "BATCHENC1");
        completed = true;
        return new EncryptionOutcome(encryptedPath, true, temporaryFile);
      } finally {
        // encrypt 抛异常时尚未把路径返回给外层，创建者负责清理半成品。
        if (!completed) {
          closeEncryptedTempFile(temporaryFile);
        }
      }
    }
    context.getAttributes().put("contentEncryptionEnabled", Boolean.FALSE);
    return new EncryptionOutcome(generatedFile, false, null);
  }

  private ExportStageResult verifyPartUpload(String expectedSha, String tempKey)
      throws IOException {
    String remotePartSha = s3ExportStorage.sha256Hex(tempKey);
    if (!expectedSha.equalsIgnoreCase(remotePartSha)) {
      s3ExportStorage.removeObject(tempKey);
      return ExportStageResult.failure(
          stage(),
          "EXPORT_STORE_PART_DIGEST_MISMATCH",
          "error.export.store.part_digest_mismatch",
          new Object[0],
          "temp object digest mismatch after upload",
          ERROR_OBJECT_MAPPER);
    }
    return null;
  }

  private ExportStageResult promoteAndVerifyFinal(
      String expectedSha, String tempKey, String objectName) {
    s3ExportStorage.copyObject(tempKey, objectName);
    String remoteFinalSha = s3ExportStorage.sha256Hex(objectName);
    if (!expectedSha.equalsIgnoreCase(remoteFinalSha)) {
      s3ExportStorage.removeObject(objectName);
      s3ExportStorage.removeObject(tempKey);
      return ExportStageResult.failure(
          stage(),
          "EXPORT_STORE_FINAL_DIGEST_MISMATCH",
          "error.export.store.final_digest_mismatch",
          new Object[0],
          "final object digest mismatch after promote",
          ERROR_OBJECT_MAPPER);
    }
    s3ExportStorage.removeObject(tempKey);
    return null;
  }

  private ExportStageResult commitStoredObject(
      ExportJobContext context, String objectName, String tempKey, Path generatedFile)
      throws IOException {
    context.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, objectName);
    context.getAttributes().put(PipelineRuntimeKeys.TEMP_OBJECT_NAME, tempKey);
    context.getAttributes().put(PipelineRuntimeKeys.EXPORT_STORE_COMMITTED, Boolean.TRUE);
    Files.deleteIfExists(generatedFile);
    return ExportStageResult.success(stage());
  }

  /** ADR-026 dry-run：本地计算 sha256，不上传，让 attributes 完整给下游验收。 */
  private ExportStageResult executeDryRun(ExportJobContext context) {
    Object generatedFilePath = attribute(context, PipelineRuntimeKeys.GENERATED_FILE_PATH);
    if (generatedFilePath instanceof String pathText && Texts.hasText(pathText)) {
      try {
        Path generatedFile = Path.of(pathText);
        if (Files.exists(generatedFile)) {
          context
              .getAttributes()
              .put(PipelineRuntimeKeys.CHECKSUM_TYPE, FileChecksumType.SHA_256.code());
          Map<String, Object> checksumAttributes = context.getAttributes();
          String fileChecksum = sha256Hex(generatedFile);
          checksumAttributes.put(PipelineRuntimeKeys.CHECKSUM_VALUE, fileChecksum);
          Files.deleteIfExists(generatedFile);
        }
      } catch (Exception ex) {
        SwallowedExceptionLogger.warn(StoreStep.class, "catch:dry_run_store_failure", ex);
        return ExportStageResult.failure(
            stage(),
            "EXPORT_STORE_FAILED",
            "error.export.store.failed",
            new Object[] {ex.getMessage()},
            ex.getMessage(),
            ERROR_OBJECT_MAPPER);
      }
    }
    if (context != null) {
      context.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "dry-run/no-upload");
      context.getAttributes().put(PipelineRuntimeKeys.EXPORT_STORE_COMMITTED, Boolean.TRUE);
    }
    return ExportStageResult.success(stage());
  }

  private record EncryptionOutcome(
      Path uploadPath, boolean encrypted, PrivateTempFiles.LockedTempFile temporaryFile) {}

  private Map<String, Object> templateSecurity(ExportJobContext context) {
    Object templateConfig = attribute(context, PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfig instanceof Map<?, ?> map) {
      Map<String, Object> security = new LinkedHashMap<>();
      security.put("content_encryption_enabled", map.get("content_encryption_enabled"));
      security.put("encryption_key_ref", map.get("encryption_key_ref"));
      security.put(KEY_DOWNLOAD_REQUIRES_APPROVAL, map.get(KEY_DOWNLOAD_REQUIRES_APPROVAL));
      return security;
    }
    return Map.of();
  }

  private Map<String, Object> attributes(ExportJobContext context) {
    return Optional.ofNullable(context).map(ExportJobContext::getAttributes).orElse(null);
  }

  private Object attribute(ExportJobContext context, String key) {
    return Optional.ofNullable(attributes(context)).map(attrs -> attrs.get(key)).orElse(null);
  }

  private String resolveText(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value);
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text : fallback;
  }

  private String sha256Hex(Path path) throws NoSuchAlgorithmException, IOException {
    MessageDigest messageDigest = MessageDigest.getInstance(FileChecksumType.SHA_256.code());
    byte[] buffer = new byte[8192];
    try (InputStream inputStream = Files.newInputStream(path)) {
      int read;
      while ((read = inputStream.read(buffer)) >= 0) {
        if (read > 0) {
          messageDigest.update(buffer, 0, read);
        }
      }
    }
    byte[] digest = messageDigest.digest();
    StringBuilder builder = new StringBuilder();
    for (byte item : digest) {
      builder.append(String.format("%02x", item));
    }
    return builder.toString();
  }
}
