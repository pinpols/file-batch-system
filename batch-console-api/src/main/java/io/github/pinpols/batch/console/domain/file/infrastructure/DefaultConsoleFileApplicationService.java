package io.github.pinpols.batch.console.domain.file.infrastructure;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.web.request.ArchiveFileRequest;
import io.github.pinpols.batch.console.domain.file.web.request.DeleteFileRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileArrivalGroupActionRequest;
import io.github.pinpols.batch.console.domain.file.web.request.PresignDownloadFileRequest;
import io.github.pinpols.batch.console.domain.file.web.request.RedispatchFileRequest;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileOperationResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.ConsoleJobOpsSupport;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalTargetBinding;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.response.file.ConsolePresignDownloadResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件治理 BFF：把 console 的文件操作 HTTP 请求转发到 orchestrator {@code /internal/files/**}，承担自由文本清洗与审批门控。
 *
 * <p>操作集：{@code archive / delete / redispatch / presignDownload / operateArrivalGroup /
 * presignUpload / confirmArrival}。其中：
 *
 * <ul>
 *   <li><b>presignDownload 两阶段</b>：请求未带 {@code approvalId} 时先发审批（返回 approvalNo，前端保存后再调）； 带 {@code
 *       approvalId} 时先 {@link #requireApprovedApproval} 校验审批已通过或已执行，再拿 presign URL。 与 {@link
 *       DefaultConsoleFileGovernanceService} 里加密文件走 console 代理 URL 的逻辑配合， 保证敏感文件下载全程有审批留痕。
 *   <li><b>请求三件套</b>：所有下游调用都带 {@code Idempotency-Key / X-Request-Id / X-Trace-Id} （与 {@link
 *       ConsoleJobOpsSupport} 协议一致）。
 *   <li><b>文本入参清洗</b>：channelCode / reason / operatorId 经 {@link ConsoleTextSanitizer#safeInput}
 *       截断并过滤控制字符再落到下游。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFileApplicationService implements ConsoleFileApplicationService {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final OrchestratorApprovalClient approvalClient;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  // P0-2 (ADR audit 2026-05-14): 所有租户参数走 guard 解析，禁止信任 body/query 中的 tenantId；
  // 非全局角色账号若 body tenantId 与 JWT 不一致直接 FORBIDDEN，跨租户操作被拦截。
  private final ConsoleTenantGuard tenantGuard;
  private final FileRecordMapper fileRecordMapper;
  private final BatchObjectStore objectStore;
  private final S3StorageProperties s3StorageProperties;

  @Override
  public ConsoleFileOperationResponse archive(ArchiveFileRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    FileExecContext ctx =
        FileExecContext.builder()
            .tenantId(tenantId)
            .fileId(request.getFileId())
            .reason(request.getReason())
            .idempotencyKey(idempotencyKey)
            .operation("archive")
            .build();
    return executeFileOperation(ctx);
  }

  @Override
  public ConsoleFileOperationResponse delete(DeleteFileRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    FileExecContext ctx =
        FileExecContext.builder()
            .tenantId(tenantId)
            .fileId(request.getFileId())
            .reason(request.getReason())
            .idempotencyKey(idempotencyKey)
            .operation("delete")
            .build();
    return executeFileOperation(ctx);
  }

  @Override
  public ConsoleFileOperationResponse redispatch(
      RedispatchFileRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    FileExecContext ctx =
        FileExecContext.builder()
            .tenantId(tenantId)
            .fileId(request.getFileId())
            .channelCode(request.getChannelCode())
            .reason(request.getReason())
            .idempotencyKey(idempotencyKey)
            .operation("redispatch")
            .build();
    return executeFileOperation(ctx);
  }

  @Override
  public ConsolePresignDownloadResponse presignDownload(
      PresignDownloadFileRequest request, String idempotencyKey) {
    if (request.getApprovalId() == null || request.getApprovalId().isBlank()) {
      ApprovalSubmitContext approvalCtx =
          ApprovalSubmitContext.builder()
              .approvalType("DOWNLOAD")
              .actionType("DOWNLOAD")
              .targetType("FILE")
              .targetId(String.valueOf(request.getFileId()))
              .payload(request)
              .approvalReason(request.getReason())
              .idempotencyKey(idempotencyKey)
              .build();
      return submitApproval(approvalCtx);
    }
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    // 审批二次校验绑定到本次下载的 fileId（targetType=FILE），与提交侧写入的 targetId 对称，
    // 消灭"任一已 APPROVED 的下载审批单解锁任意加密文件"的同租越权（此前 File 版漏了该绑定）。
    approvalClient.requireApprovedApproval(
        tenantId, request.getApprovalId(), ApprovalTargetBinding.file(request.getFileId()));
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
    FileDownloadResponse response =
        restClient
            .post()
            .uri("/internal/files/{fileId}/presign", request.getFileId())
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                new FileOperationRequest(
                    tenantId,
                    null,
                    ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                    requestMetadata.traceId(),
                    ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                    request.getApprovalId()))
            .retrieve()
            .body(FileDownloadResponse.class);
    return response == null
        ? null
        : new ConsolePresignDownloadResponse(null, response.downloadUrl());
  }

  @Override
  public ConsoleFileOperationResponse operateArrivalGroup(
      FileArrivalGroupActionRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
    FileOperationResponse response =
        restClient
            .post()
            .uri(
                "/internal/files/arrival-groups/{fileGroupCode}/actions",
                request.getFileGroupCode())
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                new ArrivalGroupOperationRequest(
                    tenantId,
                    request.getAction(),
                    ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                    requestMetadata.traceId(),
                    ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                    request.getExtendWaitSeconds()))
            .retrieve()
            .body(FileOperationResponse.class);
    return response == null ? null : new ConsoleFileOperationResponse(response.status());
  }

  @Override
  public Map<String, Object> presignUpload(
      String tenantId, String channelCode, String fileName, String idempotencyKey) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", resolvedTenantId);
    body.put("channelCode", ConsoleTextSanitizer.safeInput(channelCode, 128));
    body.put("fileName", ConsoleTextSanitizer.safeInput(fileName, 255));
    body.put("operatorId", ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64));
    body.put("traceId", requestMetadata.traceId());
    return restClient
        .post()
        .uri("/internal/files/presign-upload")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(body)
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }

  @Override
  public ConsoleFileOperationResponse uploadContent(
      String tenantId, Long fileId, MultipartFile file, String idempotencyKey) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    if (fileId == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file.id_required");
    }
    if (file == null || file.isEmpty()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file.content_required");
    }
    Map<String, Object> fileRecord =
        fileRecordMapper.selectFileRecordById(resolvedTenantId, fileId);
    if (fileRecord == null || fileRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    String storageType = stringValue(fileRecord.get("storage_type"));
    if ("LOCAL".equalsIgnoreCase(storageType)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "content upload requires object-store backed file record");
    }
    String storagePath = stringValue(fileRecord.get("storage_path"));
    if (!Texts.hasText(storagePath)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.storage_path_missing");
    }
    String bucket = stringValue(fileRecord.get("storage_bucket"));
    if (!Texts.hasText(bucket)) {
      bucket = s3StorageProperties.getBucket();
    }
    String contentType = file.getContentType();
    if (!Texts.hasText(contentType)) {
      contentType = stringValue(fileRecord.get("mime_type"));
    }
    if (!Texts.hasText(contentType)) {
      contentType = "application/octet-stream";
    }
    try (InputStream inputStream = file.getInputStream()) {
      objectStore.put(bucket, storagePath, inputStream, file.getSize(), contentType);
      return new ConsoleFileOperationResponse("UPLOADED");
    } catch (IOException exception) {
      throw new IllegalStateException("failed to open upload stream", exception);
    }
  }

  @Override
  public ConsoleFileOperationResponse confirmArrival(
      String tenantId, Long fileId, String idempotencyKey) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    FileExecContext ctx =
        FileExecContext.builder()
            .tenantId(resolvedTenantId)
            .fileId(fileId)
            .reason("tenant confirmed arrival")
            .idempotencyKey(idempotencyKey)
            .operation("confirm-arrival")
            .build();
    return executeFileOperation(ctx);
  }

  @Builder
  private record FileExecContext(
      String tenantId,
      Long fileId,
      String channelCode,
      String reason,
      String idempotencyKey,
      String operation,
      String approvalId) {}

  @Builder
  private record ApprovalSubmitContext(
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      Object payload,
      String approvalReason,
      String idempotencyKey) {}

  private ConsoleFileOperationResponse executeFileOperation(FileExecContext ctx) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
    FileOperationResponse response =
        restClient
            .post()
            .uri("/internal/files/{fileId}/" + ctx.operation(), ctx.fileId())
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, ctx.idempotencyKey())
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                new FileOperationRequest(
                    ctx.tenantId(),
                    ConsoleTextSanitizer.safeInput(ctx.channelCode(), 128),
                    ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                    requestMetadata.traceId(),
                    ConsoleTextSanitizer.safeInput(ctx.reason(), 512),
                    ctx.approvalId()))
            .retrieve()
            .body(FileOperationResponse.class);
    return response == null ? null : new ConsoleFileOperationResponse(response.status());
  }

  private ConsolePresignDownloadResponse submitApproval(ApprovalSubmitContext ctx) {
    // 保留 File 版原有租户处理：直接取 body tenantId（不经 tenantGuard.resolveTenant），仅消除复制。
    String approvalNo =
        approvalClient.submitApproval(
            ApprovalSubmitCommand.builder()
                .tenantId(extractTenantId(ctx.payload()))
                .approvalType(ctx.approvalType())
                .actionType(ctx.actionType())
                .targetType(ctx.targetType())
                .targetId(ctx.targetId())
                .payloadJson(JsonUtils.toJson(ctx.payload()))
                .approvalReason(ctx.approvalReason())
                .idempotencyKey(ctx.idempotencyKey())
                .build());
    return new ConsolePresignDownloadResponse(approvalNo, null);
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String extractTenantId(Object payload) {
    if (payload instanceof PresignDownloadFileRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof ArchiveFileRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof DeleteFileRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof RedispatchFileRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof FileArrivalGroupActionRequest request) {
      return request.getTenantId();
    }
    return null;
  }

  private record FileOperationRequest(
      String tenantId,
      String channelCode,
      String operatorId,
      String traceId,
      String reason,
      String approvalId) {}

  private record FileOperationResponse(String status) {}

  private record FileDownloadResponse(String downloadUrl) {}

  private record ArrivalGroupOperationRequest(
      String tenantId,
      String action,
      String operatorId,
      String traceId,
      String reason,
      Long extendWaitSeconds) {}
}
