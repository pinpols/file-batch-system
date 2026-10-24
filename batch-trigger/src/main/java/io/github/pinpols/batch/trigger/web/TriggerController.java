package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import io.github.pinpols.batch.trigger.config.TriggerApiAdmissionGuard;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;
import io.github.pinpols.batch.trigger.web.request.ScheduledOccurrenceCatchUpRequest;
import io.github.pinpols.batch.trigger.web.request.TriggerCatchUpRequest;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 触发器核心 REST 控制器，对外暴露任务手动触发与补单审批接口。 每个请求必须携带幂等键请求头；服务进入 draining 状态时，所有写入操作均拒绝并返回 {@code
 * STATE_CONFLICT}，调用方应做好重试或降级处理。
 */
@RestController
@RequestMapping("/api/triggers")
@RequiredArgsConstructor
public class TriggerController {

  private final TriggerService triggerService;
  private final TriggerGracefulShutdown gracefulShutdown;
  private final TriggerApiAdmissionGuard apiAdmissionGuard;
  private final TriggerApiKeyVerifier apiKeyVerifier;

  @PostMapping("/launch")
  public CommonResponse<LaunchResponse> launch(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestHeader(value = CommonConstants.DEFAULT_REQUEST_ID_HEADER, required = false)
          String requestId,
      @RequestHeader(value = CommonConstants.DEFAULT_TRACE_ID_HEADER, required = false)
          String traceId,
      @Valid @RequestBody TriggerLaunchRequest request) {
    TriggerApiKeyPrincipal externalSource = externalEventSourcePrincipal();
    if (EmptyChecks.isNotNull(externalSource)
        && (request.getTriggerType() != TriggerType.EVENT
            || !apiKeyVerifier.authorizesExternalEvent(
                externalSource, request.getTenantId(), request.getExternalEventSource()))) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.trigger.external_event_source_forbidden");
    }
    if (gracefulShutdown.isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.draining");
    }
    String finalRequestId =
        EmptyChecks.isBlank(requestId) ? IdGenerator.newBusinessNo("req") : requestId;
    String finalTraceId = EmptyChecks.isBlank(traceId) ? IdGenerator.newTraceId() : traceId;
    TriggerLaunchCommand launchCommand =
        new TriggerLaunchCommand(request, idempotencyKey, finalRequestId, finalTraceId);
    return apiAdmissionGuard.execute(
        () -> CommonResponse.success(triggerService.launch(launchCommand)));
  }

  @PostMapping("/events")
  public CommonResponse<ExternalEventLaunchResponse> launchEvent(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestHeader(value = CommonConstants.DEFAULT_REQUEST_ID_HEADER, required = false)
          String requestId,
      @RequestHeader(value = CommonConstants.DEFAULT_TRACE_ID_HEADER, required = false)
          String traceId,
      @Valid @RequestBody ExternalEventLaunchRequest request) {
    TriggerApiKeyPrincipal source = externalEventSourcePrincipal();
    if (EmptyChecks.isNull(source)
        || !apiKeyVerifier.authorizesExternalEvent(
            source, request.getTenantId(), source.keyName())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.trigger.external_event_source_forbidden");
    }
    if (gracefulShutdown.isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.draining");
    }
    String finalRequestId =
        EmptyChecks.isBlank(requestId) ? IdGenerator.newBusinessNo("event") : requestId;
    String finalTraceId = EmptyChecks.isBlank(traceId) ? IdGenerator.newTraceId() : traceId;
    ExternalEventLaunchCommand command = new ExternalEventLaunchCommand(
        request, source.keyName(), idempotencyKey, finalRequestId, finalTraceId);
    return apiAdmissionGuard.execute(
        () -> CommonResponse.success(triggerService.launchExternalEvent(command)));
  }

  @GetMapping("/events/status")
  public CommonResponse<ExternalEventLaunchStatus> externalEventStatus(
      @RequestParam String tenantId,
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
    TriggerApiKeyPrincipal source = externalEventSourcePrincipal();
    if (EmptyChecks.isNull(source)
        || !apiKeyVerifier.authorizesExternalEvent(source, tenantId, source.keyName())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.trigger.external_event_source_forbidden");
    }
    ExternalEventLaunchStatus status =
        triggerService.findExternalEventStatus(tenantId, source.keyName(), idempotencyKey);
    return CommonResponse.success(status);
  }

  @PostMapping("/scheduled-occurrences/catch-up")
  public CommonResponse<ScheduledOccurrenceCatchUpResponse> catchUpRejectedOccurrence(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestHeader(value = CommonConstants.DEFAULT_REQUEST_ID_HEADER, required = false)
          String requestId,
      @RequestHeader(value = CommonConstants.DEFAULT_TRACE_ID_HEADER, required = false)
          String traceId,
      @Valid @RequestBody ScheduledOccurrenceCatchUpRequest request) {
    if (gracefulShutdown.isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.draining");
    }
    String finalRequestId =
        EmptyChecks.isBlank(requestId) ? IdGenerator.newBusinessNo("req") : requestId;
    String finalTraceId = EmptyChecks.isBlank(traceId) ? IdGenerator.newTraceId() : traceId;
    ScheduledOccurrenceCatchUpCommand command = new ScheduledOccurrenceCatchUpCommand(
        request, idempotencyKey, finalRequestId, finalTraceId);
    return apiAdmissionGuard.execute(
        () -> CommonResponse.success(triggerService.catchUpRejectedOccurrence(command)));
  }

  /**
   * 查询异步 launch 的确定性状态。
   *
   * <p>客户端在 POST 超时后使用原幂等键查询，避免把不确定的超时结果再次当作新请求提交。
   */
  @GetMapping("/launch/status")
  public CommonResponse<TriggerLaunchStatus> launchStatus(
      @RequestParam String tenantId,
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
    TriggerApiKeyPrincipal externalSource = externalEventSourcePrincipal();
    if (EmptyChecks.isNull(externalSource)) {
      return CommonResponse.success(triggerService.findLaunchStatus(tenantId, idempotencyKey));
    }
    if (!apiKeyVerifier.authorizesExternalEvent(
        externalSource, tenantId, externalSource.keyName())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.trigger.external_event_source_forbidden");
    }
    TriggerLaunchStatus launchStatus =
        triggerService.findLaunchStatus(tenantId, idempotencyKey, externalSource.keyName());
    return CommonResponse.success(launchStatus);
  }

  private static TriggerApiKeyPrincipal externalEventSourcePrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (EmptyChecks.isNotNull(authentication)
        && authentication.getPrincipal() instanceof TriggerApiKeyPrincipal principal) {
      return principal;
    }
    return null;
  }

  @PostMapping("/catch-up/approve")
  public CommonResponse<LaunchResponse> approveCatchUp(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TriggerCatchUpRequest request) {
    if (gracefulShutdown.isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.draining");
    }
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId(request.getTenantId());
    command.setRequestId(request.getRequestId());
    command.setPendingId(request.getPendingId());
    command.setReason(request.getReason());
    command.setIdempotencyKey(idempotencyKey);
    return CommonResponse.success(triggerService.approvePendingCatchUp(command));
  }
}
