package com.example.batch.console.domain.file.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.ObjectNotFoundException;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.file.application.ConsoleFileDownloadApplicationService;
import com.example.batch.console.domain.file.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.file.mapper.FileErrorRecordMapper;
import com.example.batch.console.domain.file.mapper.FileRecordMapper;
import com.example.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import com.example.batch.console.domain.file.query.FileErrorRecordQuery;
import com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 文件下载端点：console 侧代理 MinIO 下载，承担审批门控 + 按需解密，对应 {@code DefaultConsoleFileGovernanceService}
 * 里加密文件"不直接 presign S3，走 console 代理 URL"的安全路径。
 *
 * <p>提供 2 个下载入口：
 *
 * <ul>
 *   <li>{@link #download}：单文件二进制流下载。按文件模板的 {@code download_requires_approval} 或 {@code
 *       content_encryption_enabled} 标志决定是否强制 approvalId；带 approvalId 则先远程校验 APPROVED/EXECUTED
 *       状态。响应用 {@code InputStreamResource + contentLength=-1} 实现真流式传输——避免一次性加载大文件进堆内存 OOM。
 *   <li>{@link #exportFileErrors}：文件错误记录 CSV 导出。按 RFC 4180 规则 escape 含逗号/双引号/换行的字段 （双引号包裹 +
 *       内部双引号转义），防 CSV 注入或解析歧义。
 * </ul>
 *
 * <p>加解密路径：{@code batchSecurityProperties.bypass-mode=true} 时跳过审批 + 跳过解密（仅测试环境用）； 生产环境加密文件走 {@link
 * BatchObjectCryptoService#decryptIfNeeded} 解密后再流给客户端。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFileDownloadApplicationService
    implements ConsoleFileDownloadApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final FileRecordMapper fileRecordMapper;
  private final FileErrorRecordMapper fileErrorRecordMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final S3StorageProperties s3StorageProperties;
  // R2-P0-5：复用 Spring 管理的对象存储 bean（其内部 S3Client 共享 Apache HttpClient 连接池 + 后台线程）。
  // 之前每次 download() 都 new 一个 S3Client，各自带连接池 + 非守护线程，并发下载下持续堆积 socket/线程。
  private final BatchObjectStore objectStore;
  private final BatchObjectCryptoService cryptoService;
  private final BatchSecurityProperties batchSecurityProperties;
  private final OrchestratorInternalRestClient orchestratorInternalRestClient;

  @Override
  public ResponseEntity<InputStreamResource> download(
      String tenantId, Long fileId, String approvalId) {
    String effectiveTenant = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> fileRecord = fileRecordMapper.selectFileRecordById(effectiveTenant, fileId);
    if (fileRecord == null || fileRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    Map<String, Object> security = templateSecurity(effectiveTenant, fileId);
    if (requiresDownloadApproval(security)
        && !Texts.hasText(approvalId)
        && !batchSecurityProperties.isBypassMode()) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.approval.id_required_for_download");
    }
    if (Texts.hasText(approvalId)) {
      requireApprovedApproval(effectiveTenant, approvalId, fileId);
    }
    String bucket = stringValue(fileRecord.get("storage_bucket"));
    if (!Texts.hasText(bucket)) {
      bucket = s3StorageProperties.getBucket();
    }
    String objectName = stringValue(fileRecord.get("storage_path"));
    if (!Texts.hasText(objectName)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.storage_path_missing");
    }
    String fileName = stringValue(fileRecord.get("file_name"));
    String contentType = stringValue(fileRecord.get("mime_type"));
    if (!Texts.hasText(contentType)) {
      contentType = "application/octet-stream";
    }
    try {
      InputStream inputStream = objectStore.get(bucket, objectName);
      InputStream payload =
          batchSecurityProperties.isBypassMode()
              ? inputStream
              : cryptoService.decryptIfNeeded(inputStream);
      InputStreamResource resource =
          new InputStreamResource(payload) {
            @Override
            public long contentLength() {
              return -1;
            }
          };
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment()
                  .filename(fileName == null ? objectName : fileName)
                  .build()
                  .toString())
          .contentType(MediaType.parseMediaType(contentType))
          .body(resource);
    } catch (ObjectNotFoundException exception) {
      // 存储中对象不存在(NoSuchKey,常见于 ingress 导入文件处理后已被消费)→ 404 优雅提示,
      // 不是 500 系统错误(操作员点下载得到的应是"内容已失效"而非崩溃)。
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.content_not_found");
    } catch (Exception exception) {
      // bucket 整体缺失(NoSuchBucket)同样视作"内容已失效"→ 404；其余存储异常按系统错误抛。
      if (isNoSuchBucket(exception)) {
        throw BizException.of(ResultCode.NOT_FOUND, "error.file.content_not_found");
      }
      throw new IllegalStateException("failed to open download stream", exception);
    }
  }

  @Override
  public ResponseEntity<InputStreamResource> exportFileErrors(
      String tenantId, Long fileId, String errorStage) {
    String effectiveTenant = tenantGuard.resolveTenant(tenantId);
    FileErrorRecordQuery query =
        FileErrorRecordQuery.ofFileAndStage(effectiveTenant, fileId, errorStage);
    List<FileErrorRecordEntity> errors = fileErrorRecordMapper.selectByQuery(query);
    StringBuilder csv = new StringBuilder();
    csv.append(
        "id,fileId,recordNo,errorStage,errorCode,errorMessage,skipped,skipAction,rawRecord,createdAt\n");
    for (FileErrorRecordEntity e : errors) {
      csv.append(e.getId())
          .append(',')
          .append(e.getFileId())
          .append(',')
          .append(e.getRecordNo() == null ? "" : e.getRecordNo())
          .append(',')
          .append(escape(e.getErrorStage()))
          .append(',')
          .append(escape(e.getErrorCode()))
          .append(',')
          .append(escape(e.getErrorMessage()))
          .append(',')
          .append(e.getSkipped() == null ? "" : e.getSkipped())
          .append(',')
          .append(escape(e.getSkipAction()))
          .append(',')
          .append(escape(e.getRawRecord()))
          .append(',')
          .append(e.getCreatedAt())
          .append('\n');
    }
    byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
    InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(bytes));
    String filename = "file-errors-" + fileId + ".csv";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
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

  /**
   * 透过 {@link BatchObjectStore} 包装后的异常链识别底层 {@code NoSuchBucket}（保留旧的桶缺失→404 语义）。
   *
   * <p>{@code S3ObjectStore} 把 NoSuchBucket 归入默认分支 {@code ObjectStoreException}，原始 {@link
   * S3Exception} 作为 cause 保留；这里 unwrap 到 {@link S3Exception} 后按 {@code
   * awsErrorDetails().errorCode()} 含 "NoSuch" 或 HTTP 404 判定。
   */
  private static boolean isNoSuchBucket(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof S3Exception s3) {
        String code = s3.awsErrorDetails() == null ? null : s3.awsErrorDetails().errorCode();
        return s3.statusCode() == 404 || (code != null && code.contains("NoSuch"));
      }
      current = current.getCause();
    }
    return false;
  }

  private Map<String, Object> templateSecurity(String tenantId, Long fileId) {
    String templateCode = fileRecordMapper.selectTemplateCodeByFileId(tenantId, fileId);
    if (!Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> security =
        fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(tenantId, templateCode);
    return security == null ? Map.of() : security;
  }

  private boolean requiresDownloadApproval(Map<String, Object> security) {
    if (batchSecurityProperties.isBypassMode() || security == null || security.isEmpty()) {
      return false;
    }
    return truthy(security.get("download_requires_approval"))
        || truthy(security.get("content_encryption_enabled"));
  }

  /**
   * 校验该审批单在本租户为 APPROVED/EXECUTED,<b>且</b>其目标资源（{@code targetType=FILE} 的 {@code targetId}）正是当前下载的
   * {@code fileId}。
   *
   * <p>不绑定 fileId 会导致同租越权:租户内任一已 APPROVED 的下载审批单都能解锁<b>任意</b>加密文件下载。 审批创建侧（{@code
   * DefaultConsoleFileApplicationService#presignDownload}）以 {@code targetType="FILE"} + {@code
   * targetId=fileId} 落库,这里据此 1:1 比对。
   */
  private void requireApprovedApproval(String tenantId, String approvalNo, Long fileId) {
    RestClient restClient = orchestratorInternalRestClient.build();
    ApprovalRecordResponse response =
        restClient
            .get()
            .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
            .retrieve()
            .body(ApprovalRecordResponse.class);
    ApprovalRecordResponse.ApprovalRecord record =
        Guard.requireFound(
            response == null ? null : response.record(), "approval request not found");
    String status = record.approvalStatus();
    if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.not_approved_yet");
    }
    if (!matchesTargetFile(record, fileId)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.approval.target_file_mismatch");
    }
  }

  /** 审批单的目标资源必须是 FILE 类型且 targetId 等于当前 fileId,否则视为越权使用他单。 */
  private boolean matchesTargetFile(ApprovalRecordResponse.ApprovalRecord record, Long fileId) {
    if (fileId == null || !"FILE".equalsIgnoreCase(record.targetType())) {
      return false;
    }
    return String.valueOf(fileId).equals(record.targetId());
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
    private record ApprovalRecord(String approvalStatus, String targetType, String targetId) {}
  }
}
