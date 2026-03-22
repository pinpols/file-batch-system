package com.example.batch.worker.exports.stage;

import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.infrastructure.MinioExportStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StoreStep implements ExportStageStep {

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
        Object generatedFilePath = context == null ? null : context.getAttributes().get("generatedFilePath");
        if (!(generatedFilePath instanceof String pathText) || !StringUtils.hasText(pathText)) {
            return ExportStageResult.failure(stage(), "EXPORT_STORE_INVALID", "export data missing");
        }
        try {
            String objectName = resolveText(context.getAttributes().get("objectName"), BatchFileConstants.EXPORT_OBJECT_PREFIX + UUID.randomUUID() + BatchFileConstants.JSON_SUFFIX);
            String tempObjectName = resolveText(context.getAttributes().get("tempObjectName"), objectName + BatchFileConstants.FILE_PART_SUFFIX);
            if (!tempObjectName.endsWith(BatchFileConstants.FILE_PART_SUFFIX)) {
                tempObjectName = tempObjectName + BatchFileConstants.FILE_PART_SUFFIX;
            }
            String fileFormatType = String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
            String contentType = switch (fileFormatType == null ? "" : fileFormatType.toUpperCase()) {
                case "DELIMITED" -> BatchFileConstants.CONTENT_TYPE_CSV;
                case "EXCEL" -> BatchFileConstants.CONTENT_TYPE_EXCEL;
                case "FIXED_WIDTH" -> BatchFileConstants.CONTENT_TYPE_TEXT_UTF8;
                case "XML" -> BatchFileConstants.CONTENT_TYPE_XML;
                default -> BatchFileConstants.CONTENT_TYPE_JSON;
            };
            Path generatedFile = Path.of(pathText);
            if (!Files.exists(generatedFile)) {
                return ExportStageResult.failure(stage(), "EXPORT_STORE_INVALID", "generated file missing");
            }

            Map<String, Object> security = templateSecurity(context);
            boolean encrypt = cryptoService.shouldEncrypt(security);
            Path uploadPath = generatedFile;
            Path encryptedPath = null;
            if (encrypt) {
                encryptedPath = Files.createTempFile(BatchFileConstants.ENCRYPTED_EXPORT_PREFIX, ".bin");
                cryptoService.encrypt(generatedFile, encryptedPath, cryptoService.resolveKeyRef(security));
                uploadPath = encryptedPath;
                context.getAttributes().put("contentEncryptionEnabled", Boolean.TRUE);
                context.getAttributes().put("encryptionKeyRef", cryptoService.resolveKeyRef(security));
                context.getAttributes().put("encryptionObjectVersion", "BATCHENC1");
            } else {
                context.getAttributes().put("contentEncryptionEnabled", Boolean.FALSE);
            }
            context.getAttributes().put("downloadRequiresApproval", security.get("download_requires_approval"));

            String expectedSha = sha256Hex(uploadPath);
            context.getAttributes().put("checksumType", "SHA-256");
            context.getAttributes().put("checksumValue", expectedSha);

            String tempKey = minioExportStorage.writeObject(tempObjectName, uploadPath, encrypt ? BatchFileConstants.CONTENT_TYPE_OCTET_STREAM : contentType);
            String remotePartSha = minioExportStorage.sha256Hex(tempKey);
            if (!expectedSha.equalsIgnoreCase(remotePartSha)) {
                minioExportStorage.removeObject(tempKey);
                if (encryptedPath != null) {
                    Files.deleteIfExists(encryptedPath);
                }
                return ExportStageResult.failure(stage(), "EXPORT_STORE_PART_DIGEST_MISMATCH", "temp object digest mismatch after upload");
            }

            minioExportStorage.copyObject(tempKey, objectName);
            String remoteFinalSha = minioExportStorage.sha256Hex(objectName);
            if (!expectedSha.equalsIgnoreCase(remoteFinalSha)) {
                minioExportStorage.removeObject(objectName);
                minioExportStorage.removeObject(tempKey);
                if (encryptedPath != null) {
                    Files.deleteIfExists(encryptedPath);
                }
                return ExportStageResult.failure(stage(), "EXPORT_STORE_FINAL_DIGEST_MISMATCH", "final object digest mismatch after promote");
            }
            minioExportStorage.removeObject(tempKey);

            context.getAttributes().put("objectName", objectName);
            context.getAttributes().put("tempObjectName", tempKey);
            context.getAttributes().put("exportStoreCommitted", Boolean.TRUE);
            Files.deleteIfExists(generatedFile);
            if (encryptedPath != null) {
                Files.deleteIfExists(encryptedPath);
            }
            return ExportStageResult.success(stage());
        } catch (Exception ex) {
            return ExportStageResult.failure(stage(), "EXPORT_STORE_FAILED", ex.getMessage());
        }
    }

    private Map<String, Object> templateSecurity(ExportJobContext context) {
        Object templateConfig = context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
        if (templateConfig instanceof Map<?, ?> map) {
            Map<String, Object> security = new java.util.LinkedHashMap<>();
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
        return StringUtils.hasText(text) && !"null".equalsIgnoreCase(text) ? text : fallback;
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
