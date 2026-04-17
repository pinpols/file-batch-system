package com.example.batch.console.infrastructure;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.config.ConsoleTriggerClientProperties;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PartitionReplayRequest;
import com.example.batch.console.web.request.TaskReplayRequest;
import com.example.batch.console.web.request.TriggerRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 作业运维操作的公共基础设施：审批提交、补偿提交、触发器委派、租户解析、事件发布。
 *
 * <p>被 trigger / recovery / approval 三个拆分服务共享，避免重复代码。
 */
@Component
@RequiredArgsConstructor
class ConsoleJobOpsSupport {

  private static final String JOB_TYPE_COMPENSATION = "COMPENSATION";

  private final RestClient.Builder restClientBuilder;
  private final ConsoleTriggerClientProperties triggerClientProperties;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final Environment environment;

  // ── constants ──────────────────────────────────────────────────────────

  static String jobTypeCompensation() {
    return JOB_TYPE_COMPENSATION;
  }

  // ── tenant resolution ──────────────────────────────────────────────────

  String resolveTenant(String requestTenantId) {
    return tenantGuard.resolveTenant(requestTenantId);
  }

  // ── event publishing ───────────────────────────────────────────────────

  void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-retries", "outbox-retry-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-deliveries", "outbox-delivery-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }

  // ── trigger delegation ─────────────────────────────────────────────────

  String delegateLaunch(
      String tenantId,
      String jobCode,
      String bizDate,
      TriggerType triggerType,
      Map<String, Object> params,
      String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(triggerClientProperties.getBaseUrl())).build();
    CommonResponse<LaunchResponse> response =
        restClient
            .post()
            .uri("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                new TriggerLaunchPayload(
                    tenantId,
                    ConsoleTextSanitizer.safeInput(jobCode, 128),
                    parseBizDate(bizDate),
                    triggerType,
                    params == null ? Map.of() : params))
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {});
    if (response == null || response.data() == null) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "trigger service returned empty response");
    }
    return response.data().instanceNo();
  }

  // ── compensation submission ────────────────────────────────────────────

  String submitCompensation(CompensationPayload payload, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    CompensationResponse response =
        restClient
            .post()
            .uri("/internal/compensations")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(payload.withTraceId(requestMetadata.traceId()))
            .retrieve()
            .body(CompensationResponse.class);
    if (response == null || response.commandNo() == null) {
      throw new BizException(
          ResultCode.SYSTEM_ERROR, "orchestrator returned empty compensation response");
    }
    return response.commandNo();
  }

  // ── recovery trigger ───────────────────────────────────────────────────

  private record RecoveryOperationResponse(String operationNo) {}

  String triggerRecovery(
      String tenantId, String uriTemplate, Long targetId, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    CommonResponse<RecoveryOperationResponse> response =
        restClient
            .post()
            .uri(uriTemplate, targetId)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(Map.of("tenantId", tenantId))
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<RecoveryOperationResponse>>() {});
    if (response == null || response.data() == null) {
      throw new BizException(
          ResultCode.SYSTEM_ERROR, "orchestrator returned empty recovery response");
    }
    return response.data().operationNo();
  }

  // ── approval submission ────────────────────────────────────────────────

  String submitApproval(ApprovalSubmitContext ctx) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ApprovalResponse response =
        restClient
            .post()
            .uri("/internal/approvals")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, ctx.idempotencyKey())
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                ApprovalRequest.of(
                    new ApprovalTarget(
                        resolveTenant(extractTenantId(ctx.payload())),
                        ctx.approvalType(),
                        ctx.actionType(),
                        ctx.targetType(),
                        ctx.targetId()),
                    ctx.payload(),
                    requestMetadata,
                    ctx.idempotencyKey(),
                    ctx.approvalReason()))
            .retrieve()
            .body(ApprovalResponse.class);
    if (response == null || !hasText(response.approvalNo())) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "approval service returned empty response");
    }
    return response.approvalNo();
  }

  void requireApprovedApproval(String tenantId, String approvalNo) {
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
    String status = response.getRecord().getApprovalStatus();
    if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
      throw new BizException(ResultCode.STATE_CONFLICT, "approval is not approved yet");
    }
  }

  // ── utility methods ────────────────────────────────────────────────────

  boolean hasText(String text) {
    return text != null && !text.isBlank();
  }

  TriggerType resolveTriggerType(String triggerTypeValue, TriggerType defaultTriggerType) {
    if (triggerTypeValue == null || triggerTypeValue.isBlank()) {
      return defaultTriggerType;
    }
    try {
      return TriggerType.valueOf(triggerTypeValue.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "unsupported triggerType: " + triggerTypeValue);
    }
  }

  LocalDate parseBizDate(String bizDate) {
    try {
      return LocalDate.parse(bizDate);
    } catch (DateTimeParseException exception) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "bizDate must use yyyy-MM-dd");
    }
  }

  LocalDate parseOptionalBizDate(String bizDate) {
    if (bizDate == null || bizDate.isBlank()) {
      return null;
    }
    return parseBizDate(bizDate);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> parsePayload(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return Map.of();
    }
    Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
    if (payloadObject instanceof Map<?, ?> payloadMap) {
      return (Map<String, Object>) payloadMap;
    }
    throw new BizException(ResultCode.INVALID_ARGUMENT, "payload must be a JSON object");
  }

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }

  private String extractTenantId(Object payload) {
    if (payload instanceof TriggerRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof CompensationCommandRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof DeadLetterReplayRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof TaskReplayRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof PartitionReplayRequest request) {
      return request.getTenantId();
    }
    if (payload instanceof ConsoleCatchUpApprovalRequest request) {
      return request.getTenantId();
    }
    return null;
  }

  // ── inner DTOs ─────────────────────────────────────────────────────────

  record ApprovalSubmitContext(
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      Object payload,
      String approvalReason,
      String idempotencyKey) {}

  private record TriggerLaunchPayload(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      TriggerType triggerType,
      Map<String, Object> params) {}

  private record ApprovalTarget(
      String tenantId,
      String approvalType,
      String actionType,
      String targetType,
      String targetId) {}

  @Getter
  private static final class ApprovalRequest {
    private final String tenantId;
    private final String approvalType;
    private final String actionType;
    private final String targetType;
    private final String targetId;
    private final String payloadJson;
    private final String requesterId;
    private final String sourceTraceId;
    private final String sourceIdempotencyKey;
    private final String approvalReason;

    private ApprovalRequest(
        ApprovalTarget target,
        String payloadJson,
        String requesterId,
        String sourceTraceId,
        String sourceIdempotencyKey,
        String approvalReason) {
      this.tenantId = target.tenantId();
      this.approvalType = target.approvalType();
      this.actionType = target.actionType();
      this.targetType = target.targetType();
      this.targetId = target.targetId();
      this.payloadJson = payloadJson;
      this.requesterId = requesterId;
      this.sourceTraceId = sourceTraceId;
      this.sourceIdempotencyKey = sourceIdempotencyKey;
      this.approvalReason = approvalReason;
    }

    private static ApprovalRequest of(
        ApprovalTarget target,
        Object payload,
        ConsoleRequestMetadata metadata,
        String idempotencyKey,
        String approvalReason) {
      return new ApprovalRequest(
          target,
          JsonUtils.toJson(payload),
          ConsoleTextSanitizer.safeInput(metadata.operatorId(), 64),
          metadata.traceId(),
          idempotencyKey,
          ConsoleTextSanitizer.safeInput(approvalReason, 512));
    }
  }

  private record ApprovalResponse(String approvalNo) {}

  @Getter
  @Setter
  @NoArgsConstructor
  static class ApprovalRecordResponse {
    private ApprovalRecord record;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  static class ApprovalRecord {
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

  @Getter
  @Builder(toBuilder = true)
  static class CompensationPayload {
    private final String tenantId;
    private final String compensationType;
    private final Long targetId;
    private final String targetInstanceNo;
    private final String jobCode;
    private final LocalDate bizDate;
    private final String batchNo;
    private final Long relatedFileId;
    private final String channelCode;
    private final String reason;
    private final String operatorId;
    private final String approvalId;
    private final String strategy;
    private final String traceId;

    CompensationPayload withTraceId(String currentTraceId) {
      return toBuilder().traceId(currentTraceId).build();
    }
  }

  record CompensationResponse(String commandNo) {}
}
