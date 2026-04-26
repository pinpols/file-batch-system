package com.example.batch.trigger.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.web.request.TriggerCatchUpRequest;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

  @PostMapping("/launch")
  public CommonResponse<LaunchResponse> launch(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestHeader(value = CommonConstants.DEFAULT_REQUEST_ID_HEADER, required = false)
          String requestId,
      @RequestHeader(value = CommonConstants.DEFAULT_TRACE_ID_HEADER, required = false)
          String traceId,
      @Valid @RequestBody TriggerLaunchRequest request) {
    if (gracefulShutdown.isDraining()) {
      throw new BizException(ResultCode.STATE_CONFLICT, "trigger service is draining");
    }
    String finalRequestId =
        requestId == null || requestId.isBlank() ? IdGenerator.newBusinessNo("req") : requestId;
    String finalTraceId = traceId == null || traceId.isBlank() ? IdGenerator.newTraceId() : traceId;
    return CommonResponse.success(
        triggerService.launch(
            new TriggerLaunchCommand(request, idempotencyKey, finalRequestId, finalTraceId)));
  }

  @PostMapping("/catch-up/approve")
  public CommonResponse<LaunchResponse> approveCatchUp(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TriggerCatchUpRequest request) {
    if (gracefulShutdown.isDraining()) {
      throw new BizException(ResultCode.STATE_CONFLICT, "trigger service is draining");
    }
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId(request.getTenantId());
    command.setRequestId(request.getRequestId());
    command.setReason(request.getReason());
    command.setIdempotencyKey(idempotencyKey);
    return CommonResponse.success(triggerService.approvePendingCatchUp(command));
  }
}
