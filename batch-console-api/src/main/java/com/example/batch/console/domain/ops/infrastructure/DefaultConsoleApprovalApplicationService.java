package com.example.batch.console.domain.ops.infrastructure;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.file.application.ConsoleFileApplicationService;
import com.example.batch.console.domain.file.web.request.PresignDownloadFileRequest;
import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.domain.job.application.ConsoleJobApplicationService;
import com.example.batch.console.domain.job.web.request.CompensationCommandRequest;
import com.example.batch.console.domain.ops.application.ConsoleApprovalApplicationService;
import com.example.batch.console.domain.ops.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.domain.ops.web.response.ConsoleBatchApprovalResultResponse;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.ops.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.response.file.ConsolePresignDownloadResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 审批决策入口：approve 成功后按 {@code actionType} 自动执行下游业务。
 *
 * <p>核心编排（{@link #approve}）：
 *
 * <ol>
 *   <li>从 orchestrator 拉审批记录，非 {@code PENDING} 直接返回 approvalNo（幂等——重复调用不二次执行业务）。
 *   <li>远程调 {@code /internal/approvals/{no}/approve} 把状态推进为 APPROVED。
 *   <li>按 {@code actionType} 分派到对应 application service 执行真实业务：
 *       <ul>
 *         <li>{@code COMPENSATION} → {@link ConsoleJobApplicationService#compensation}
 *         <li>{@code DLQ_REPLAY} → {@link ConsoleJobApplicationService#replayDeadLetter}
 *         <li>{@code DOWNLOAD} → {@link ConsoleFileApplicationService#presignDownload}
 *         <li>{@code CATCH_UP} → {@link ConsoleJobApplicationService#approveCatchUp}
 *         <li>{@code BATCH_DAY_REPLAY} → orchestrator {@code
 *             /internal/orchestrator/batch-day-replay/sessions/{id}/approve}（ADR-020 Stage 3
 *             审批接入；payload 含 sessionId / tenantId）
 *       </ul>
 *   <li>业务执行成功后调 {@code /executed} 把审批推进为 EXECUTED——失败则不推进，允许重试同一 approvalNo。
 * </ol>
 *
 * <p>批量接口 {@link #batchApprove} / {@link #batchReject}：逐项独立 try/catch，单项失败不中断全批； 错误消息经 {@link
 * ConsoleTextSanitizer#safeDisplay} 清洗后回传，防异常栈泄露内部细节。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleApprovalApplicationService implements ConsoleApprovalApplicationService {

  // P2-1(2026-05-16):删除未实际使用的 RestClient.Builder 字段(原本就是空注入,死代码)。
  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleJobApplicationService consoleJobApplicationService;
  private final ConsoleFileApplicationService consoleFileApplicationService;

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
          case "BATCH_DAY_REPLAY" -> {
            // ADR-020 Stage 3 审批接入：payload = {"sessionId":<long>, "tenantId":<str>}。
            // approve 后转发到 orchestrator 推进 session 状态 PENDING_APPROVAL → RUNNING；
            // 复用 batch-day-replay/sessions/{id}/approve 端点，approver 取审批人 operatorId。
            BatchDayReplayApprovalPayload payload =
                JsonUtils.fromJson(record.getPayloadJson(), BatchDayReplayApprovalPayload.class);
            if (payload == null
                || payload.getSessionId() == null
                || payload.getTenantId() == null
                || payload.getTenantId().isBlank()) {
              throw BizException.of(
                  ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
            }
            RestClient batchDayReplayClient = orchestratorInternalRestClient.build();
            batchDayReplayClient
                .post()
                .uri(
                    "/internal/orchestrator/batch-day-replay/sessions/{id}/approve"
                        + "?tenantId={tenantId}&approver={approver}",
                    payload.getSessionId(),
                    payload.getTenantId(),
                    operatorId == null ? "system" : operatorId)
                .retrieve()
                .toBodilessEntity();
            yield approvalNo;
          }
          default ->
              throw BizException.of(
                  ResultCode.INVALID_ARGUMENT,
                  "error.common.invalid_argument_detail",
                  "unsupported approval action: " + actionType);
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
        SwallowedExceptionLogger.warn(
            DefaultConsoleApprovalApplicationService.class, "catch:Exception", ex);

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
        SwallowedExceptionLogger.warn(
            DefaultConsoleApprovalApplicationService.class, "catch:Exception", ex);

        results.add(
            new ConsoleBatchApprovalResultResponse(
                approvalNo, false, ConsoleTextSanitizer.safeDisplay(ex.getMessage(), 512)));
      }
    }
    return List.copyOf(results);
  }

  private ApprovalRecordResponse loadApproval(String tenantId, String approvalNo) {
    RestClient restClient = orchestratorInternalRestClient.build();
    ApprovalRecordResponse response =
        restClient
            .get()
            .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
            .retrieve()
            .body(ApprovalRecordResponse.class);
    Guard.requireFound(
        response == null ? null : response.getRecord(), "approval request not found");
    return response;
  }

  private void approveRemote(String tenantId, String approvalNo, String operatorId, String reason) {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
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
    RestClient restClient = orchestratorInternalRestClient.build();
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
    RestClient restClient = orchestratorInternalRestClient.build();
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

  /**
   * ADR-020 Stage 3 审批接入：BATCH_DAY_REPLAY 类型的 approval_command 在 payload_json 中携带的字段。 提交者
   * (BatchDayReplayService.submit) 在 PENDING_APPROVAL 创建 session 时同步创建 approval_command， 把
   * sessionId / tenantId 写到 payload；approver 走 /api/console/approvals/{no}/approve 时 dispatch 到
   * orchestrator 的 batch-day-replay/sessions/{id}/approve 推进 RUNNING。
   */
  @NoArgsConstructor
  @Getter
  @Setter
  private static class BatchDayReplayApprovalPayload {
    private Long sessionId;
    private String tenantId;
  }
}
