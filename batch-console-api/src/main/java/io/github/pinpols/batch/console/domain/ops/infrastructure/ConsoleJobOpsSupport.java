package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalTargetBinding;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.CompensationPayload;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.command.PartitionReplayRequest;
import io.github.pinpols.batch.console.shared.command.TaskReplayRequest;
import io.github.pinpols.batch.console.shared.command.TriggerRequest;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    // P0-1(2026-05-16):此前直接 restClientBuilder.baseUrl(...).build() 漏装
    // X-Internal-Secret,生产 bypass=false 后 trigger 侧 401。换走
    // TriggerInternalRestClient 统一注入 secret + 超时。
    RestClient restClient = triggerInternalRestClient.build();
    CommonResponse<LaunchResponse> response = restClient
        .post()
        .uri("/api/triggers/launch")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(new TriggerLaunchPayload(
            command.tenantId(),
            ConsoleTextSanitizer.safeInput(command.jobCode(), 128),
            parseBizDate(command.bizDate()),
            command.triggerType(),
            command.params() == null ? Map.of() : command.params()))
        .retrieve()
        .body(new ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {});
    if (response == null || response.data() == null) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    return response.data().instanceNo();
  }

  @Override
  public String submitCompensation(CompensationPayload payload, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
    CompensationResponse response = restClient
        .post()
        .uri("/internal/compensations")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
        .body(payload.toBuilder().traceId(requestMetadata.traceId()).build())
        .retrieve()
        .body(CompensationResponse.class);
    if (response == null || response.commandNo() == null) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.orchestrator.empty_compensation");
    }
    return response.commandNo();
  }

  private record RecoveryOperationResponse(String operationNo) {}

  @Override
  public String triggerRecovery(
      String tenantId, String uriTemplate, Long targetId, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient = orchestratorInternalRestClient.build();
    CommonResponse<RecoveryOperationResponse> response = restClient
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

  @Override
  public String submitApproval(ApprovalSubmitContext ctx) {
    return approvalClient.submitApproval(ApprovalSubmitCommand.builder()
        .tenantId(resolveTenant(extractTenantId(ctx.payload())))
        .approvalType(ctx.approvalType())
        .actionType(ctx.actionType())
        .targetType(ctx.targetType())
        .targetId(ctx.targetId())
        .payloadJson(JsonUtils.toJson(ctx.payload()))
        .approvalReason(ctx.approvalReason())
        .idempotencyKey(ctx.idempotencyKey())
        .build());
  }

  /**
   * 校验审批已通过态（APPROVED/EXECUTED）。作业运维路径保留原有的<b>不绑定目标</b>行为（{@link ApprovalTargetBinding#none()}）——
   * 本次重构只做去重不改此路径对外行为；作业侧的目标绑定加固是独立的后续项。
   */
  @Override
  public void requireApprovedApproval(String tenantId, String approvalNo) {
    approvalClient.requireApprovedApproval(tenantId, approvalNo, ApprovalTargetBinding.none());
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
      return TriggerType.valueOf(triggerTypeValue.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
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

  private record TriggerLaunchPayload(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      TriggerType triggerType,
      Map<String, Object> params) {}

  record CompensationResponse(String commandNo) {}
}
