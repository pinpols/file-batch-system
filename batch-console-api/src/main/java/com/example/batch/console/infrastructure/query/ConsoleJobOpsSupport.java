package com.example.batch.console.infrastructure.query;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.infrastructure.ops.OrchestratorInternalRestClient;
import com.example.batch.console.infrastructure.ops.TriggerInternalRestClient;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.job.CompensationCommandRequest;
import com.example.batch.console.web.request.job.PartitionReplayRequest;
import com.example.batch.console.web.request.job.TaskReplayRequest;
import com.example.batch.console.web.request.job.TriggerRequest;
import com.example.batch.console.web.request.ops.ConsoleCatchUpApprovalRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 作业运维操作的公共基础设施：审批提交、补偿提交、recovery 触发、trigger launch 委派、租户解析、事件广播。
 *
 * <p>被 {@code ConsoleJobTriggerService} / {@code DefaultConsoleJobRecoveryService} / {@code
 * ConsoleApprovalApplicationService} 三个拆分服务共享，避免重复代码。
 *
 * <p>关键约定：
 *
 * <ul>
 *   <li><b>双 baseUrl 路由</b>：{@link #delegateLaunch} 走 {@code triggerClientProperties}（batch-trigger
 *       服务）， 其余（compensation / recovery / approval）走 {@code
 *       orchestratorClientProperties}（batch-orchestrator 服务） ——console 作为 BFF 不直连 DB，一律通过内部 HTTP
 *       调用后端服务。
 *   <li><b>请求追踪三件套</b>：所有下游 RestClient 调用都带 {@code Idempotency-Key} / {@code X-Request-Id} / {@code
 *       X-Trace-Id}（见 {@link CommonConstants}），用户侧重试幂等 + 全链路追踪。
 *   <li><b>publishRefresh 批量广播</b>：触发型操作成功后一次性发 5 个领域事件 （job-instances / workflow-runs /
 *       outbox-retries / outbox-deliveries / summary）， 让前端多个面板并行刷新，避免前端逐个轮询。
 *   <li><b>审批二次校验</b>：{@link #requireApprovedApproval} 同时接受 {@code APPROVED} 与 {@code EXECUTED}
 *       状态——已执行视为审批通过（幂等），调用方重放同一 approval 不被拒绝。
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ConsoleJobOpsSupport {

  private static final String JOB_TYPE_COMPENSATION = "COMPENSATION";

  // P2-1(2026-05-16):删除 RestClient.Builder 字段直接注入 — 所有 client 构造都走专用
  // OrchestratorInternalRestClient / TriggerInternalRestClient,后者已是 ObjectProvider 模式。
  private final OrchestratorInternalRestClient orchestratorInternalRestClient;

  /** P0-1(2026-05-16):trigger 调用统一走带 X-Internal-Secret 的 client,prod bypass=false 不再 401。 */
  private final TriggerInternalRestClient triggerInternalRestClient;

  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;

  public static String jobTypeCompensation() {
    return JOB_TYPE_COMPENSATION;
  }

  public String resolveTenant(String requestTenantId) {
    return tenantGuard.resolveTenant(requestTenantId);
  }

  public void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-retries", "outbox-retry-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-deliveries", "outbox-delivery-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }

  public String delegateLaunch(
      String tenantId,
      String jobCode,
      String bizDate,
      TriggerType triggerType,
      Map<String, Object> params,
      String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    // P0-1(2026-05-16):此前直接 restClientBuilder.baseUrl(...).build() 漏装
    // X-Internal-Secret,生产 bypass=false 后 trigger 侧 401。换走
    // TriggerInternalRestClient 统一注入 secret + 超时。
    RestClient restClient = triggerInternalRestClient.build();
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
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    return response.data().instanceNo();
  }

  public String submitCompensation(CompensationPayload payload, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
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
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.orchestrator.empty_compensation");
    }
    return response.commandNo();
  }

  private record RecoveryOperationResponse(String operationNo) {}

  public String triggerRecovery(
      String tenantId, String uriTemplate, Long targetId, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
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
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.orchestrator.empty_recovery");
    }
    return response.data().operationNo();
  }

  public String submitApproval(ApprovalSubmitContext ctx) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
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
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.approval.empty_response");
    }
    return response.approvalNo();
  }

  public void requireApprovedApproval(String tenantId, String approvalNo) {
    RestClient restClient = orchestratorInternalRestClient.build();
    ApprovalRecordResponse response =
        restClient
            .get()
            .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
            .retrieve()
            .body(ApprovalRecordResponse.class);
    ApprovalRecord record =
        Guard.requireFound(
            response == null ? null : response.getRecord(), "approval request not found");
    String status = record.getApprovalStatus();
    if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.not_approved_yet");
    }
  }

  public boolean hasText(String text) {
    return text != null && !text.isBlank();
  }

  public TriggerType resolveTriggerType(String triggerTypeValue, TriggerType defaultTriggerType) {
    if (triggerTypeValue == null || triggerTypeValue.isBlank()) {
      return defaultTriggerType;
    }
    try {
      return TriggerType.valueOf(triggerTypeValue.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "unsupported triggerType: " + triggerTypeValue);
    }
  }

  public LocalDate parseBizDate(String bizDate) {
    try {
      return LocalDate.parse(bizDate);
    } catch (DateTimeParseException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_format");
    }
  }

  public LocalDate parseOptionalBizDate(String bizDate) {
    if (bizDate == null || bizDate.isBlank()) {
      return null;
    }
    return parseBizDate(bizDate);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> parsePayload(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return Map.of();
    }
    Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
    if (payloadObject instanceof Map<?, ?> payloadMap) {
      return (Map<String, Object>) payloadMap;
    }
    throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.payload_must_be_object");
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

  @Builder
  public record ApprovalSubmitContext(
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
  public static class CompensationPayload {
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

    /** §5.5 — 补跑结果版本策略（CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE）。 */
    private final String resultPolicy;

    /** §5.5 — 补跑配置版本策略（USE_ORIGINAL_CONFIG / USE_LATEST_CONFIG / USE_SPECIFIED_VERSION）。 */
    private final String configVersionPolicy;

    /** USE_SPECIFIED_VERSION 时使用的具体 job_definition_version。 */
    private final Integer configVersion;

    CompensationPayload withTraceId(String currentTraceId) {
      return toBuilder().traceId(currentTraceId).build();
    }
  }

  record CompensationResponse(String commandNo) {}
}
