package com.example.batch.console.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.console.service.ConsoleFileDownloadApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.config.MinioStorageProperties;
import com.example.batch.console.mapper.FileRecordMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class DefaultConsoleFileDownloadApplicationService implements ConsoleFileDownloadApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final FileRecordMapper fileRecordMapper;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;
    private final MinioStorageProperties minioStorageProperties;
    private final BatchObjectCryptoService cryptoService;
    private final BatchSecurityProperties batchSecurityProperties;
    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;

    @Override
    public ResponseEntity<InputStreamResource> download(String tenantId, Long fileId, String approvalId) {
        String effectiveTenant = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> fileRecord = fileRecordMapper.selectFileRecordById(effectiveTenant, fileId);
        if (fileRecord == null || fileRecord.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file record not found");
        }
        Map<String, Object> security = templateSecurity(effectiveTenant, fileId);
        if (requiresDownloadApproval(security) && !StringUtils.hasText(approvalId) && !batchSecurityProperties.isTestingOpen()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "approvalId is required for download on this file template");
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
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
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
}
