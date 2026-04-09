package com.example.batch.console.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.console.application.ConsoleFileDownloadApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.query.FileErrorRecordQuery;
import com.example.batch.console.mapper.FileErrorRecordMapper;
import com.example.batch.console.mapper.FileRecordMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleFileDownloadApplicationService} 的默认实现：
 * 校验租户与审批上下文后，从 MinIO 流式读取对象（必要时解密）。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFileDownloadApplicationService implements ConsoleFileDownloadApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final FileRecordMapper fileRecordMapper;
    private final FileErrorRecordMapper fileErrorRecordMapper;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;
    private final MinioStorageProperties minioStorageProperties;
    private final BatchObjectCryptoService cryptoService;
    private final BatchSecurityProperties batchSecurityProperties;
    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final Environment environment;

    @Override
    public ResponseEntity<InputStreamResource> download(String tenantId, Long fileId, String approvalId) {
        String effectiveTenant = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> fileRecord = fileRecordMapper.selectFileRecordById(effectiveTenant, fileId);
        if (fileRecord == null || fileRecord.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file record not found");
        }
        Map<String, Object> security = templateSecurity(effectiveTenant, fileId);
        if (requiresDownloadApproval(security) && !StringUtils.hasText(approvalId) && !batchSecurityProperties.isTestingOpen()) {
            throw new BizException(ResultCode.BUSINESS_ERROR, "approvalId is required for download on this file template");
        }
        if (StringUtils.hasText(approvalId)) {
            requireApprovedApproval(effectiveTenant, approvalId);
        }
        String bucket = stringValue(fileRecord.get("storage_bucket"));
        if (!StringUtils.hasText(bucket)) {
            bucket = minioStorageProperties.getBucket();
        }
        String objectName = stringValue(fileRecord.get("storage_path"));
        if (!StringUtils.hasText(objectName)) {
            throw new BizException(ResultCode.STATE_CONFLICT, "file storage path is missing");
        }
        String fileName = stringValue(fileRecord.get("file_name"));
        String contentType = stringValue(fileRecord.get("mime_type"));
        if (!StringUtils.hasText(contentType)) {
            contentType = "application/octet-stream";
        }
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(minioStorageProperties.getEndpoint())
                    .credentials(minioStorageProperties.getAccessKey(), minioStorageProperties.getSecretKey())
                    .build();
            InputStream inputStream = client.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );
            InputStream payload = batchSecurityProperties.isTestingOpen() ? inputStream : cryptoService.decryptIfNeeded(inputStream);
            InputStreamResource resource = new InputStreamResource(payload) {
                @Override
                public long contentLength() {
                    return -1;
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName == null ? objectName : fileName).build().toString())
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to open download stream", exception);
        }
    }

    @Override
    public ResponseEntity<InputStreamResource> exportFileErrors(String tenantId, Long fileId, String errorStage) {
        String effectiveTenant = tenantGuard.resolveTenant(tenantId);
        FileErrorRecordQuery query = new FileErrorRecordQuery(effectiveTenant, fileId, errorStage,
                null, null, null);
        List<FileErrorRecordEntity> errors = fileErrorRecordMapper.selectByQuery(query);
        StringBuilder csv = new StringBuilder();
        csv.append("id,fileId,recordNo,errorStage,errorCode,errorMessage,skipped,skipAction,rawRecord,createdAt\n");
        for (FileErrorRecordEntity e : errors) {
            csv.append(e.getId()).append(',')
               .append(e.getFileId()).append(',')
               .append(e.getRecordNo() == null ? "" : e.getRecordNo()).append(',')
               .append(escape(e.getErrorStage())).append(',')
               .append(escape(e.getErrorCode())).append(',')
               .append(escape(e.getErrorMessage())).append(',')
               .append(e.getSkipped() == null ? "" : e.getSkipped()).append(',')
               .append(escape(e.getSkipAction())).append(',')
               .append(escape(e.getRawRecord())).append(',')
               .append(e.getCreatedAt()).append('\n');
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(bytes));
        String filename = "file-errors-" + fileId + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Map<String, Object> templateSecurity(String tenantId, Long fileId) {
        String templateCode = fileRecordMapper.selectTemplateCodeByFileId(tenantId, fileId);
        if (!StringUtils.hasText(templateCode)) {
            return Map.of();
        }
        Map<String, Object> security = fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(tenantId, templateCode);
        return security == null ? Map.of() : security;
    }

    private boolean requiresDownloadApproval(Map<String, Object> security) {
        if (batchSecurityProperties.isTestingOpen() || security == null || security.isEmpty()) {
            return false;
        }
        return truthy(security.get("download_requires_approval")) || truthy(security.get("content_encryption_enabled"));
    }

    private void requireApprovedApproval(String tenantId, String approvalNo) {
        RestClient restClient = restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
        ApprovalRecordResponse response = restClient.get()
                .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
                .retrieve()
                .body(ApprovalRecordResponse.class);
        if (response == null || response.record() == null) {
            throw new BizException(ResultCode.NOT_FOUND, "approval request not found");
        }
        String status = response.record().approvalStatus();
        if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
            throw new BizException(ResultCode.STATE_CONFLICT, "approval is not approved yet");
        }
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ApprovalRecordResponse(ApprovalRecord record) {
        private record ApprovalRecord(String approvalStatus) {
        }
    }

    private String resolveUrl(String url) {
        return environment.resolvePlaceholders(url);
    }
}
