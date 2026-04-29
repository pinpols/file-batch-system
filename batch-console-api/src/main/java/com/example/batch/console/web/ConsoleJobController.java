package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.application.ConsoleJobApprovalService;
import com.example.batch.console.application.ConsoleJobRecoveryService;
import com.example.batch.console.application.ConsoleJobTriggerService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.web.request.BatchDayCatchUpRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PartitionReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TaskReplayRequest;
import com.example.batch.console.web.request.TriggerRequest;
import com.example.batch.console.web.response.ConsoleBatchDayCatchUpResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台作业运维 REST：触发、补偿、重跑、死信回放、Catch-Up 审批。
 *
 * <p>触发作业对所有已认证用户开放（含 ROLE_TENANT_USER），其余运维操作需 ROLE_ADMIN。
 */
@RestController
@Validated
@RequestMapping("/api/console/jobs")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Idempotent
public class ConsoleJobController {

  private final ConsoleJobTriggerService triggerService;
  private final ConsoleJobRecoveryService recoveryService;
  private final ConsoleJobApprovalService approvalService;
  private final ConsoleResponseFactory responseFactory;

  /** 手工触发作业运行（所有已认证用户均可触发）。dryRun=true 时仅校验不执行。 */
  @PostMapping("/trigger")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_USER')")
  public CommonResponse<?> trigger(
      @RequestHeader(value = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      @Valid @RequestBody TriggerRequest request) {
    if (request.isDryRun()) {
      return responseFactory.success(triggerService.dryRunTrigger(request));
    }
    requireIdempotencyKey(idempotencyKey);
    return responseFactory.success(triggerService.trigger(request, idempotencyKey));
  }

  /** 批量触发多个作业。 */
  @PostMapping("/batch-trigger")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_USER')")
  public CommonResponse<List<Map<String, Object>>> batchTrigger(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestBody @NotEmpty @Size(max = 50) List<@Valid TriggerRequest> items) {
    return responseFactory.success(triggerService.batchTrigger(items, idempotencyKey));
  }

  /** 登记补偿命令。 */
  @PostMapping("/compensations")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> compensation(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody CompensationCommandRequest request) {
    return responseFactory.success(recoveryService.compensation(request, idempotencyKey));
  }

  /** 执行补偿。 */
  @PostMapping("/compensate")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> compensate(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody CompensateRequest request) {
    return responseFactory.success(recoveryService.compensate(request, idempotencyKey));
  }

  /** 重跑实例或分区。 */
  @PostMapping("/rerun")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> rerun(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody RerunRequest request) {
    return responseFactory.success(recoveryService.rerun(request, idempotencyKey));
  }

  /** 死信重放。 */
  @PostMapping("/dead-letters/replay")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> replayDeadLetter(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody DeadLetterReplayRequest request) {
    return responseFactory.success(recoveryService.replayDeadLetter(request, idempotencyKey));
  }

  /** 任务重放（job_task 粒度）。 */
  @PostMapping("/tasks/replay")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> replayTask(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TaskReplayRequest request) {
    return responseFactory.success(recoveryService.replayTask(request, idempotencyKey));
  }

  /** 分区重放（job_partition 粒度）。 */
  @PostMapping("/partitions/replay")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> replayPartition(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody PartitionReplayRequest request) {
    return responseFactory.success(recoveryService.replayPartition(request, idempotencyKey));
  }

  /** 审批通过 Catch-Up 请求。 */
  @PostMapping("/catch-up/approve")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> approveCatchUp(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConsoleCatchUpApprovalRequest request) {
    return responseFactory.success(approvalService.approveCatchUp(request, idempotencyKey));
  }

  /** 按批量日发起 catch-up。 */
  @PostMapping("/batch-days/{bizDate}/catchup")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<ConsoleBatchDayCatchUpResponse> batchDayCatchUp(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String bizDate,
      @Valid @RequestBody BatchDayCatchUpRequest request) {
    return responseFactory.success(
        approvalService.catchUpBatchDay(bizDate, request, idempotencyKey));
  }

  private void requireIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw BizException.of(
          ResultCode.MISSING_IDEMPOTENCY_KEY,
          "error.common.missing_idempotency_key_detail",
          CommonErrorMessages.MISSING_IDEMPOTENCY_KEY);
    }
  }
}
