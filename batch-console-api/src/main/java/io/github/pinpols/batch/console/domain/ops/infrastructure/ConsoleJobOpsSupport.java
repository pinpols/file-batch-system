package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.WorkflowNodeReplayPreviewRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.WorkflowRunReplayRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleWorkflowNodeReplayPreviewResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleWorkflowRunReplayPreviewResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleReplayOperationResponse;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.CompensationPayload;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.shared.command.ConsoleScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.console.shared.command.ConsoleWorkflowLaunchCommand;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.command.PartitionReplayRequest;
import io.github.pinpols.batch.console.shared.command.TaskReplayRequest;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 作业运维操作的公共基础设施：审批提交、补偿提交、recovery 触发、trigger launch 委派、租户解析、事件广播。
 *
 * <p>被 {@code DefaultConsoleJobRecoveryService} / {@code ConsoleApprovalApplicationService} 两个拆分服务共享，避免重复代码。
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
 *
 * <p>这个类只负责 Console 到内部服务的边界适配，不直接访问控制面数据库。这样 Console 的重试、审批和事件刷新都经过同一组租户、幂等键、
 * trace 和内部鉴权规则，避免某个运维入口为了“方便”绕过审批或在重试时产生重复操作；具体业务状态仍由 orchestrator/trigger 的单一写入方决定。
 */
@Component
@RequiredArgsConstructor
public class ConsoleJobOpsSupport implements ConsoleJobOperationsPort {

  // P2-1(2026-05-16):删除 RestClient.Builder 字段直接注入 — 所有 client 构造都走专用
  // OrchestratorInternalRestClient / TriggerInternalRestClient,后者已是 ObjectProvider 模式。
  private final OrchestratorInternalRestClient orchestratorInternalRestClient;

  /** P0-1(2026-05-16):trigger 调用统一走带 X-Internal-Secret 的 client,prod bypass=false 不再 401。 */
  private final TriggerInternalRestClient triggerInternalRestClient;

  /** 共享审批客户端：submit / require-approved 都下沉到它，避免四处复制。 */
  private final OrchestratorApprovalClient approvalClient;

  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final TenantIdResolver tenantGuard;
  private final ConsoleRealtimeEventPort domainEventPublisher;

  @Override
  public String resolveTenant(String requestTenantId) {
    return tenantGuard.resolveTenant(requestTenantId);
  }

  @Override
  public void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-retries", "outbox-retry-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-deliveries", "outbox-delivery-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }

  @Override
  public String delegateLaunch(ConsoleLaunchCommand command) {
    boolean workflowTargetIsMissingOrInvalid =
        EmptyChecks.isNull(command.workflowDefinitionId()) || command.workflowDefinitionId() <= 0;
    if (workflowTargetIsMissingOrInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.workflow_target_required");
    }
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    // P0-1(2026-05-16):此前直接 restClientBuilder.baseUrl(...).build() 漏装
    // X-Internal-Secret,生产 bypass=false 后 trigger 侧 401。换走
    // TriggerInternalRestClient 统一注入 secret + 超时。
    RestClient restClient = triggerInternalRestClient.client();
    String tenantId = command.tenantId();
    Long workflowDefinitionId = command.workflowDefinitionId();
    String businessDateText = command.bizDate();
    LocalDate businessDate = parseBizDate(businessDateText);
    TriggerType triggerType = command.triggerType();
    TriggerLaunchPayload launchPayload = new TriggerLaunchPayload(
        tenantId,
        workflowDefinitionId,
        businessDate,
        triggerType,
        EmptyChecks.isNull(command.params()) ? Map.of() : command.params());
    CommonResponse<LaunchResponse> response = restClient
        .post()
        .uri("/api/triggers/launch")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(launchPayload)
        .retrieve()
        .body(new ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {});
    if (EmptyChecks.isNull(response)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    LaunchResponse launchResponse = response.data();
    if (EmptyChecks.isNull(launchResponse)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    return launchResponse.instanceNo();
  }

  @Override
  public String delegateWorkflowLaunch(ConsoleWorkflowLaunchCommand command) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = triggerInternalRestClient.client();
    WorkflowTriggerLaunchPayload launchPayload = new WorkflowTriggerLaunchPayload(
        command.tenantId(),
        command.workflowDefinitionId(),
        command.bizDate(),
        command.triggerType(),
        EmptyChecks.isNull(command.params()) ? Map.of() : command.params());
    CommonResponse<LaunchResponse> response = restClient
        .post()
        .uri("/api/triggers/launch")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(launchPayload)
        .retrieve()
        .body(new ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {});
    if (EmptyChecks.isNull(response)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    LaunchResponse launchResponse = response.data();
    if (EmptyChecks.isNull(launchResponse)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    return launchResponse.instanceNo();
  }

  @Override
  public ScheduledOccurrenceCatchUpResponse delegateScheduledOccurrenceCatchUp(
      ConsoleScheduledOccurrenceCatchUpCommand command) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    CommonResponse<ScheduledOccurrenceCatchUpResponse> response = triggerInternalRestClient
        .client()
        .post()
        .uri("/api/triggers/scheduled-occurrences/catch-up")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(new ScheduledOccurrenceCatchUpPayload(
            command.tenantId(),
            command.workflowDefinitionId(),
            command.scheduledFireTime(),
            command.bizDate(),
            command.requestedBy(),
            command.reason()))
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    if (EmptyChecks.isNull(response)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    ScheduledOccurrenceCatchUpResponse catchUpResponse = response.data();
    if (EmptyChecks.isNull(catchUpResponse)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    return catchUpResponse;
  }

  private record ScheduledOccurrenceCatchUpPayload(
      String tenantId,
      Long workflowDefinitionId,
      Instant scheduledFireTime,
      LocalDate bizDate,
      String requestedBy,
      String reason) {}

  @Override
  public String submitCompensation(CompensationPayload payload, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.client();
    CompensationPayload requestPayload = payload.toBuilder()
        .traceId(requestMetadata.traceId())
        .operatorId(requestMetadata.operatorId())
        .build();
    CompensationResponse response = restClient
        .post()
        .uri("/internal/compensations")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(requestPayload)
        .retrieve()
        .body(CompensationResponse.class);
    if (response == null || response.commandNo() == null) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.orchestrator.empty_compensation");
    }
    return response.commandNo();
  }

  @Override
  public ConsoleWorkflowNodeReplayPreviewResponse previewWorkflowNodeReplay(
      WorkflowNodeReplayPreviewRequest request) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String resolvedTenantId = resolveTenant(request.getTenantId());
    Long workflowRunId = request.getWorkflowRunId();
    Long workflowNodeRunId = request.getWorkflowNodeRunId();
    String reason = request.getReason();
    String safeReason = ConsoleTextSanitizer.safeInput(reason, 512);
    String operatorId = requestMetadata.operatorId();
    String traceId = requestMetadata.traceId();
    WorkflowNodeReplayPreviewCommand previewCommand = new WorkflowNodeReplayPreviewCommand(
        resolvedTenantId,
        workflowRunId,
        workflowNodeRunId,
        safeReason,
        operatorId,
        null,
        null,
        null,
        traceId,
        null,
        null);
    return orchestratorInternalRestClient.client()
        .post()
        .uri("/internal/compensations/workflow-node/replay-preview")
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(previewCommand)
        .retrieve()
        .body(ConsoleWorkflowNodeReplayPreviewResponse.class);
  }

  @Override
  public Long createPendingWorkflowNodeReplay(CompensationCommandRequest request) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String resolvedTenantId = resolveTenant(request.getTenantId());
    Long sourceWorkflowRunId = request.getSourceWorkflowRunId();
    Long sourceWorkflowNodeRunId = request.getSourceWorkflowNodeRunId();
    String reason = request.getReason();
    String requestedBy = requestMetadata.operatorId();
    String resultPolicy = request.getResultPolicy();
    String configVersionPolicy = request.getConfigVersionPolicy();
    Integer configVersion = request.getConfigVersion();
    String traceId = requestMetadata.traceId();
    String operationKeyText = request.getOperationKey();
    UUID operationKey = UUID.fromString(operationKeyText);
    String previewToken = request.getPreviewToken();
    WorkflowNodeReplayCommand command = new WorkflowNodeReplayCommand(
        resolvedTenantId,
        sourceWorkflowRunId,
        sourceWorkflowNodeRunId,
        reason,
        requestedBy,
        resultPolicy,
        configVersionPolicy,
        configVersion,
        traceId,
        operationKey,
        previewToken);
    WorkflowNodeReplayAdmissionResponse response = orchestratorInternalRestClient.client()
        .post()
        .uri("/internal/compensations/workflow-node/replay-operations")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, request.getOperationKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(command)
        .retrieve()
        .body(WorkflowNodeReplayAdmissionResponse.class);
    if (EmptyChecks.isNull(response) || EmptyChecks.isNull(response.replayOperationId())) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.workflow.replay_operation_not_persisted");
    }
    return response.replayOperationId();
  }

  @Override
  public ConsoleWorkflowRunReplayPreviewResponse previewWorkflowRunReplay(
      WorkflowRunReplayRequest request) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    WorkflowRunReplayCommand command = workflowRunReplayCommand(request, requestMetadata, false);
    CommonResponse<ConsoleWorkflowRunReplayPreviewResponse> response = orchestratorInternalRestClient
        .client()
        .post()
        .uri("/internal/orchestrator/workflow-replay/preview")
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(command)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    if (EmptyChecks.isNull(response) || EmptyChecks.isNull(response.data())) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.workflow.workflow_run_replay_operation_not_persisted");
    }
    return response.data();
  }

  @Override
  public Long createPendingWorkflowRunReplay(WorkflowRunReplayRequest request) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    WorkflowRunReplayCommand command = workflowRunReplayCommand(request, requestMetadata, true);
    CommonResponse<ConsoleReplayOperationResponse> response = orchestratorInternalRestClient
        .client()
        .post()
        .uri("/internal/orchestrator/workflow-replay/submit")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, request.getOperationKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(command)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    ConsoleReplayOperationResponse replayOperation = EmptyChecks.isNull(response) ? null : response.data();
    Long replayOperationId = EmptyChecks.isNull(replayOperation) ? null : replayOperation.id();
    if (EmptyChecks.isNull(replayOperationId)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.workflow.replay_operation_not_persisted");
    }
    return replayOperationId;
  }

  private WorkflowRunReplayCommand workflowRunReplayCommand(
      WorkflowRunReplayRequest request, ConsoleRequestMetadata requestMetadata, boolean submission) {
    String tenantId = resolveTenant(request.getTenantId());
    UUID operationKey = submission ? UUID.fromString(request.getOperationKey()) : null;
    Long workflowRunId = request.getWorkflowRunId();
    String reason = request.getReason();
    String safeReason = ConsoleTextSanitizer.safeInput(reason, 512);
    String requestedBy = requestMetadata.operatorId();
    String resultPolicy = request.getResultPolicy();
    String configVersionPolicy = request.getConfigVersionPolicy();
    Integer configVersion = request.getConfigVersion();
    String traceId = requestMetadata.traceId();
    String previewToken = submission ? request.getPreviewToken() : null;
    return new WorkflowRunReplayCommand(
        tenantId,
        workflowRunId,
        safeReason,
        requestedBy,
        resultPolicy,
        configVersionPolicy,
        configVersion,
        traceId,
        operationKey,
        previewToken);
  }

  private record WorkflowRunReplayCommand(
      String tenantId,
      Long workflowRunId,
      String reason,
      String requestedBy,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion,
      String traceId,
      UUID operationKey,
      String previewToken) {}

  private record WorkflowRunReplayApprovalPayload(String tenantId, Long replayOperationId) {}

  private record WorkflowNodeReplayPreviewCommand(
      String tenantId,
      Long workflowRunId,
      Long workflowNodeRunId,
      String reason,
      String requestedBy,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion,
      String traceId,
      UUID operationKey,
      String previewToken) {}

  private record WorkflowNodeReplayCommand(
      String tenantId,
      Long workflowRunId,
      Long workflowNodeRunId,
      String reason,
      String requestedBy,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion,
      String traceId,
      UUID operationKey,
      String previewToken) {}

  private record WorkflowNodeReplayAdmissionResponse(Long replayOperationId) {}

  private record RecoveryOperationResponse(String operationNo) {}

  @Override
  public String triggerRecovery(
      String tenantId, String uriTemplate, Long targetId, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.client();
    RecoveryOperationResponse response = restClient
        .post()
        .uri(uriTemplate, targetId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(Map.of("tenantId", tenantId))
        .retrieve()
        .body(RecoveryOperationResponse.class);
    String operationNo = EmptyChecks.isNull(response) ? null : response.operationNo();
    if (EmptyChecks.isBlank(operationNo)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.orchestrator.empty_recovery");
    }
    return operationNo;
  }

  @Override
  public String submitApproval(ApprovalSubmitContext ctx) {
    OrchestratorApprovalClient.ApprovalSubmitCommand.ApprovalSubmitCommandBuilder commandBuilder = ApprovalSubmitCommand.builder();
    Object approvalPayload = ctx.payload();
    String payloadTenantId = extractTenantId(approvalPayload);
    String tenantId = resolveTenant(payloadTenantId);
    commandBuilder
        .tenantId(tenantId)
        .approvalType(ctx.approvalType())
        .actionType(ctx.actionType())
        .targetType(ctx.targetType())
        .targetId(ctx.targetId());
    String payloadJson = JsonUtils.toJson(ctx.payload());
    ApprovalSubmitCommand command = commandBuilder
        .payloadJson(payloadJson)
        .approvalReason(ctx.approvalReason())
        .idempotencyKey(ctx.idempotencyKey())
        .build();
    return approvalClient.submitApproval(command);
  }

  /**
   * 校验审批已通过态、动作与完整执行载荷，防止已批准单被用于另一实例或变更后的业务参数。
   */
  @Override
  public void requireApprovedApproval(
      String tenantId, String approvalNo, String actionType, Object executionPayload) {
    approvalClient.requireApprovedExecution(tenantId, approvalNo, actionType, executionPayload);
  }

  @Override
  public boolean hasText(String text) {
    return text != null && !text.isBlank();
  }

  @Override
  public TriggerType resolveTriggerType(String triggerTypeValue, TriggerType defaultTriggerType) {
    if (triggerTypeValue == null || triggerTypeValue.isBlank()) {
      return defaultTriggerType;
    }
    try {
      String trimmedTriggerType = triggerTypeValue.trim();
      String normalizedTriggerType = trimmedTriggerType.toUpperCase();
      return TriggerType.valueOf(normalizedTriggerType);
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "unsupported triggerType: " + triggerTypeValue);
    }
  }

  @Override
  public LocalDate parseBizDate(String bizDate) {
    try {
      return LocalDate.parse(bizDate);
    } catch (DateTimeParseException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_format");
    }
  }

  @Override
  public LocalDate parseOptionalBizDate(String bizDate) {
    if (bizDate == null || bizDate.isBlank()) {
      return null;
    }
    return parseBizDate(bizDate);
  }

  @SuppressWarnings("unchecked")
  @Override
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
    Object payloadTenantId = JsonUtils.toMap(payload).get("tenantId");
    return payloadTenantId instanceof String tenantId ? tenantId : null;
  }

  private record TriggerLaunchPayload(
      String tenantId,
      Long workflowDefinitionId,
      LocalDate bizDate,
      TriggerType triggerType,
      Map<String, Object> params) {}

  private record WorkflowTriggerLaunchPayload(
      String tenantId,
      Long workflowDefinitionId,
      LocalDate bizDate,
      TriggerType triggerType,
      Map<String, Object> params) {}

  record CompensationResponse(String commandNo) {}
}
