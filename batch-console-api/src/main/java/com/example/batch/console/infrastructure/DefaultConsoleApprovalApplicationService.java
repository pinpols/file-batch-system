package com.example.batch.console.infrastructure;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleApprovalApplicationService;
import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.console.web.response.ConsoleBatchApprovalResultResponse;
import com.example.batch.console.web.response.ConsolePresignDownloadResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleApprovalApplicationService} 的默认实现： 编排器审批 HTTP
 * 调用，并在涉及文件/作业联动时委托其他应用服务。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleApprovalApplicationService implements ConsoleApprovalApplicationService {

  private final RestClient.Builder restClientBuilder;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleJobApplicationService consoleJobApplicationService;
  private final ConsoleFileApplicationService consoleFileApplicationService;
  private final Environment environment;

  @Override
  public String approve(String tenantId, String approvalNo, String operatorId, String reason) {
    ApprovalRecordResponse recordResponse = loadApproval(tenantId, approvalNo);
    ApprovalRecord record = recordResponse.getRecord();
    if (!"PENDING".equalsIgnoreCase(record.getApprovalStatus())) {
      return approvalNo;
    }
    approveRemote(tenantId, approvalNo, operatorId, reason);
    String actionType = record.getActionType();
    String result =
        switch (actionType) {
          case "COMPENSATION" -> {
            CompensationCommandRequest request =
                JsonUtils.fromJson(record.getPayloadJson(), CompensationCommandRequest.class);
            request.setApprovalId(approvalNo);
            yield consoleJobApplicationService.compensation(request, approvalNo);
          }
          case "DLQ_REPLAY" -> {
            DeadLetterReplayRequest request =
                JsonUtils.fromJson(record.getPayloadJson(), DeadLetterReplayRequest.class);
            request.setApprovalId(approvalNo);
            yield consoleJobApplicationService.replayDeadLetter(request, approvalNo);
          }
          case "DOWNLOAD" -> {
            PresignDownloadFileRequest request =
                JsonUtils.fromJson(record.getPayloadJson(), PresignDownloadFileRequest.class);
            request.setApprovalId(approvalNo);
            ConsolePresignDownloadResponse downloadResponse =
                consoleFileApplicationService.presignDownload(request, approvalNo);
            yield downloadResponse == null ? null : downloadResponse.downloadUrl();
          }
          case "CATCH_UP" -> {
            ConsoleCatchUpApprovalRequest request =
                JsonUtils.fromJson(record.getPayloadJson(), ConsoleCatchUpApprovalRequest.class);
            request.setApprovalId(approvalNo);
            yield consoleJobApplicationService.approveCatchUp(request, approvalNo);
          }
          default ->
              throw new BizException(
                  ResultCode.INVALID_ARGUMENT, "unsupported approval action: " + actionType);
        };
    markExecutedRemote(tenantId, approvalNo);
    return result;
  }

  @Override
  public String reject(String tenantId, String approvalNo, String operatorId, String reason) {
    rejectRemote(tenantId, approvalNo, operatorId, reason);
    return approvalNo;
  }

  @Override
  public List<ConsoleBatchApprovalResultResponse> batchApprove(
      String tenantId, List<String> approvalNos, String operatorId, String reason) {
    if (approvalNos == null || approvalNos.isEmpty()) {
      return List.of();
    }
    List<ConsoleBatchApprovalResultResponse> results = new ArrayList<>();
    for (String approvalNo : approvalNos) {
      try {
        approve(tenantId, approvalNo, operatorId, reason);
        results.add(new ConsoleBatchApprovalResultResponse(approvalNo, true, "APPROVED"));
      } catch (Exception ex) {
        results.add(
            new ConsoleBatchApprovalResultResponse(
                approvalNo, false, ConsoleTextSanitizer.safeDisplay(ex.getMessage(), 512)));
      }
    }
    return List.copyOf(results);
  }

  @Override
  public List<ConsoleBatchApprovalResultResponse> batchReject(
      String tenantId, List<String> approvalNos, String operatorId, String reason) {
    if (approvalNos == null || approvalNos.isEmpty()) {
      return List.of();
    }
    List<ConsoleBatchApprovalResultResponse> results = new ArrayList<>();
    for (String approvalNo : approvalNos) {
      try {
        reject(tenantId, approvalNo, operatorId, reason);
        results.add(new ConsoleBatchApprovalResultResponse(approvalNo, true, "REJECTED"));
      } catch (Exception ex) {
        results.add(
            new ConsoleBatchApprovalResultResponse(
                approvalNo, false, ConsoleTextSanitizer.safeDisplay(ex.getMessage(), 512)));
      }
    }
    return List.copyOf(results);
  }

  private ApprovalRecordResponse loadApproval(String tenantId, String approvalNo) {
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ApprovalRecordResponse response =
        restClient
            .get()
            .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
            .retrieve()
            .body(ApprovalRecordResponse.class);
    Guard.requireFound(
        response == null || response.getRecord() == null, "approval request not found");
    return response;
  }

  private void approveRemote(String tenantId, String approvalNo, String operatorId, String reason) {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    restClient
        .post()
        .uri("/internal/approvals/{approvalNo}/approve", approvalNo)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, metadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, metadata.traceId())
        .body(
            new ApprovalActionRequest(
                tenantId,
                ConsoleTextSanitizer.safeInput(operatorId, 64),
                ConsoleTextSanitizer.safeInput(reason, 512)))
        .retrieve()
        .toBodilessEntity();
  }

  private void rejectRemote(String tenantId, String approvalNo, String operatorId, String reason) {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    restClient
        .post()
        .uri("/internal/approvals/{approvalNo}/reject", approvalNo)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, metadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, metadata.traceId())
        .body(
            new ApprovalActionRequest(
                tenantId,
                ConsoleTextSanitizer.safeInput(operatorId, 64),
                ConsoleTextSanitizer.safeInput(reason, 512)))
        .retrieve()
        .toBodilessEntity();
  }

  private void markExecutedRemote(String tenantId, String approvalNo) {
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    restClient
        .post()
        .uri("/internal/approvals/{approvalNo}/executed", approvalNo)
        .body(new ApprovalTenantRequest(tenantId))
        .retrieve()
        .toBodilessEntity();
  }

  private record ApprovalActionRequest(String tenantId, String operatorId, String reason) {}

  private record ApprovalTenantRequest(String tenantId) {}

  @Getter
  @Setter
  @NoArgsConstructor
  private static class ApprovalRecordResponse {
    private ApprovalRecord record;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  private static class ApprovalRecord {
    private String tenantId;
    private String approvalNo;
    private String approvalType;
    private String actionType;
    private String targetType;
    private String targetId;
    private String payloadJson;
    private String approvalStatus;
    private String requesterId;
    private String approverId;
    private String rejectionReason;
    private String approvalReason;
    private String sourceTraceId;
    private String sourceIdempotencyKey;
  }

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }
}
