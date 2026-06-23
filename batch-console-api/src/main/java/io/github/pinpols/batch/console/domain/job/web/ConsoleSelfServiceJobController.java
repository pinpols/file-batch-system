package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService.CompensationParam;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService.RerunParam;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户自助重跑/补偿申请：提交审批工单，由管理员审批后执行。 */
@RestController
@Validated
@RequestMapping("/api/console/self-service/jobs")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleSelfServiceJobController {

  private final ConsoleSelfServiceJobService selfServiceJobService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @PostMapping("/rerun-request")
  public CommonResponse<String> requestRerun(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody SelfServiceRerunRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    RerunParam param =
        new RerunParam(
            request.tenantId(),
            request.jobCode(),
            request.bizDate(),
            request.targetInstanceNo(),
            request.reason());
    return responseFactory.success(
        selfServiceJobService.requestRerun(param, operator, idempotencyKey));
  }

  @PostMapping("/compensation-request")
  public CommonResponse<String> requestCompensation(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody SelfServiceCompensationRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    CompensationParam param =
        new CompensationParam(
            request.tenantId(),
            request.jobCode(),
            request.bizDate(),
            request.compensationType(),
            request.targetInstanceNo(),
            request.reason());
    return responseFactory.success(
        selfServiceJobService.requestCompensation(param, operator, idempotencyKey));
  }

  record SelfServiceRerunRequest(
      @NotBlank String tenantId,
      @NotBlank @Size(max = 128) String jobCode,
      @NotBlank String bizDate,
      String targetInstanceNo,
      @Size(max = 512) String reason) {}

  record SelfServiceCompensationRequest(
      @NotBlank String tenantId,
      @NotBlank @Size(max = 128) String jobCode,
      @NotBlank String bizDate,
      String compensationType,
      String targetInstanceNo,
      @Size(max = 512) String reason) {}
}
