package com.example.batch.worker.exports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.infrastructure.MinioExportStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 导出存储阶段：将生成的临时文件上传至对象存储（先写 .part 再 copy 提升），并完成 SHA-256 校验。 */
@Component
public class StoreStep implements ExportStageStep {

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  private final MinioExportStorage minioExportStorage;
  private final BatchObjectCryptoService cryptoService;

  public StoreStep(MinioExportStorage minioExportStorage, BatchObjectCryptoService cryptoService) {
    this.minioExportStorage = minioExportStorage;
    this.cryptoService = cryptoService;
  }

  @Override
  public ExportStage stage() {
    return ExportStage.STORE;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    Object generatedFilePath =
        context == null ? null : context.getAttributes().get("generatedFilePath");
    if (!(generatedFilePath instanceof String pathText) || !Texts.hasText(pathText)) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_STORE_INVALID",
          "error.export.store.invalid",
          new Object[] {"export data missing"},
          "export data missing",
          ERROR_OBJECT_MAPPER);
    }
    try {
      String objectName = resolveObjectName(context);
      String tempObjectName = resolveTempObjectName(context, objectName);
      String contentType = resolveContentType(context);
      Path generatedFile = Path.of(pathText);
      if (!Files.exists(generatedFile)) {
        return ExportStageResult.failure(
            stage(),
            "EXPORT_STORE_INVALID",
            "error.export.store.invalid",
            new Object[] {"generated file missing"},
            "generated file missing",
            ERROR_OBJECT_MAPPER);
      }

      EncryptionOutcome encryption = encryptIfNeeded(context, generatedFile);
      Path uploadPath = encryption.uploadPath();
      Path encryptedPath = encryption.encryptedPath();
      boolean encrypt = encryption.encrypted();

      String expectedSha = sha256Hex(uploadPath);
      context.getAttributes().put("checksumType", "SHA-256");
      context.getAttributes().put("checksumValue", expectedSha);

      String tempKey =
          minioExportStorage.writeObject(
              tempObjectName,
              uploadPath,
              encrypt ? BatchFileConstants.CONTENT_TYPE_OCTET_STREAM : contentType);
      ExportStageResult partVerification = verifyPartUpload(expectedSha, tempKey, encryptedPath);
      if (partVerification != null) {
        return partVerification;
      }

      ExportStageResult finalVerification =
          promoteAndVerifyFinal(expectedSha, tempKey, objectName, encryptedPath);
      if (finalVerification != null) {
        return finalVerification;
      }

      return commitStoredObject(context, objectName, tempKey, generatedFile, encryptedPath);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(StoreStep.class, "catch:Exception", ex);

      return ExportStageResult.failure(
          stage(),
          "EXPORT_STORE_FAILED",
          "error.export.store.failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          ERROR_OBJECT_MAPPER);
    }
  }

  private String resolveObjectName(ExportJobContext context) {
    return resolveText(
        context.getAttributes().get("objectName"),
        BatchFileConstants.EXPORT_OBJECT_PREFIX
            + UUID.randomUUID()
            + BatchFileConstants.JSON_SUFFIX);
  }

  private String resolveTempObjectName(ExportJobContext context, String objectName) {
    String tempObjectName =
        resolveText(
            context.getAttributes().get("tempObjectName"),
            objectName + BatchFileConstants.FILE_PART_SUFFIX);
    if (!tempObjectName.endsWith(BatchFileConstants.FILE_PART_SUFFIX)) {
      tempObjectName = tempObjectName + BatchFileConstants.FILE_PART_SUFFIX;
    }
    return tempObjectName;
  }

  private String resolveContentType(ExportJobContext context) {
    String fileFormatType =
        String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
    return switch (fileFormatType == null ? "" : fileFormatType.toUpperCase()) {
      case "DELIMITED" -> BatchFileConstants.CONTENT_TYPE_CSV;
      case "EXCEL" -> BatchFileConstants.CONTENT_TYPE_EXCEL;
      case "FIXED_WIDTH" -> BatchFileConstants.CONTENT_TYPE_TEXT_UTF8;
      case "XML" -> BatchFileConstants.CONTENT_TYPE_XML;
      default -> BatchFileConstants.CONTENT_TYPE_JSON;
    };
  }

  private EncryptionOutcome encryptIfNeeded(ExportJobContext context, Path generatedFile)
      throws Exception {
    Map<String, Object> security = templateSecurity(context);
    context
        .getAttributes()
        .put("downloadRequiresApproval", security.get("download_requires_approval"));
    boolean encrypt = cryptoService.shouldEncrypt(security);
    if (encrypt) {
      Path encryptedPath = Files.createTempFile(BatchFileConstants.ENCRYPTED_EXPORT_PREFIX, ".bin");
      cryptoService.encrypt(generatedFile, encryptedPath, cryptoService.resolveKeyRef(security));
      context.getAttributes().put("contentEncryptionEnabled", Boolean.TRUE);
      context.getAttributes().put("encryptionKeyRef", cryptoService.resolveKeyRef(security));
      context.getAttributes().put("encryptionObjectVersion", "BATCHENC1");
      return new EncryptionOutcome(encryptedPath, encryptedPath, true);
    }
    context.getAttributes().put("contentEncryptionEnabled", Boolean.FALSE);
    return new EncryptionOutcome(generatedFile, null, false);
  }

  private ExportStageResult verifyPartUpload(String expectedSha, String tempKey, Path encryptedPath)
      throws Exception {
    String remotePartSha = minioExportStorage.sha256Hex(tempKey);
    if (!expectedSha.equalsIgnoreCase(remotePartSha)) {
      minioExportStorage.removeObject(tempKey);
      if (encryptedPath != null) {
        Files.deleteIfExists(encryptedPath);
      }
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
      String expectedSha, String tempKey, String objectName, Path encryptedPath) throws Exception {
    minioExportStorage.copyObject(tempKey, objectName);
    String remoteFinalSha = minioExportStorage.sha256Hex(objectName);
    if (!expectedSha.equalsIgnoreCase(remoteFinalSha)) {
      minioExportStorage.removeObject(objectName);
      minioExportStorage.removeObject(tempKey);
      if (encryptedPath != null) {
        Files.deleteIfExists(encryptedPath);
      }
      return ExportStageResult.failure(
          stage(),
          "EXPORT_STORE_FINAL_DIGEST_MISMATCH",
          "error.export.store.final_digest_mismatch",
          new Object[0],
          "final object digest mismatch after promote",
          ERROR_OBJECT_MAPPER);
    }
    minioExportStorage.removeObject(tempKey);
    return null;
  }

  private ExportStageResult commitStoredObject(
      ExportJobContext context,
      String objectName,
      String tempKey,
      Path generatedFile,
      Path encryptedPath)
      throws Exception {
    context.getAttributes().put("objectName", objectName);
    context.getAttributes().put("tempObjectName", tempKey);
    context.getAttributes().put("exportStoreCommitted", Boolean.TRUE);
    Files.deleteIfExists(generatedFile);
    if (encryptedPath != null) {
      Files.deleteIfExists(encryptedPath);
    }
    return ExportStageResult.success(stage());
  }

  private record EncryptionOutcome(Path uploadPath, Path encryptedPath, boolean encrypted) {}

  private Map<String, Object> templateSecurity(ExportJobContext context) {
    Object templateConfig =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfig instanceof Map<?, ?> map) {
      Map<String, Object> security = new LinkedHashMap<>();
      security.put("content_encryption_enabled", map.get("content_encryption_enabled"));
      security.put("encryption_key_ref", map.get("encryption_key_ref"));
      security.put("download_requires_approval", map.get("download_requires_approval"));
      return security;
    }
    return Map.of();
  }

  private String resolveText(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value);
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text : fallback;
  }

  private String sha256Hex(Path path) throws Exception {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[8192];
    try (var inputStream = Files.newInputStream(path)) {
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
