package com.example.batch.worker.exports.stage;

import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.infrastructure.MinioExportStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StoreStep implements ExportStageStep {

    private final MinioExportStorage minioExportStorage;

    public StoreStep(MinioExportStorage minioExportStorage) {
        this.minioExportStorage = minioExportStorage;
    }

    @Override
    public ExportStage stage() {
        return ExportStage.STORE;
    }

    @Override
    public ExportStageResult execute(ExportJobContext context) {
        Object generatedContent = context == null ? null : context.getAttributes().get("generatedContent");
        if (!(generatedContent instanceof String content) || !StringUtils.hasText(content)) {
            return ExportStageResult.failure(stage(), "EXPORT_STORE_INVALID", "export data missing");
        }
        try {
            String objectName = String.valueOf(context.getAttributes().get("objectName"));
            String tempObjectName = String.valueOf(context.getAttributes().get("tempObjectName"));
            String fileFormatType = String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
            String contentType = "DELIMITED".equalsIgnoreCase(fileFormatType) ? "text/csv" : "application/json";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            context.getAttributes().put("checksumType", "SHA-256");
            context.getAttributes().put("checksumValue", sha256Hex(bytes));
            String tempStoredObject = minioExportStorage.writeObject(tempObjectName, bytes, contentType);
            minioExportStorage.copyObject(tempStoredObject, objectName);
            minioExportStorage.removeObject(tempStoredObject);
            context.getAttributes().put("tempObjectName", tempStoredObject);
            return ExportStageResult.success(stage());
        } catch (Exception ex) {
            return ExportStageResult.failure(stage(), "EXPORT_STORE_FAILED", ex.getMessage());
        }
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        byte[] digest = messageDigest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte item : digest) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }
}
