package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.command.BatchDayOperationCommand;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayCatchupPreviewResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayHistoricalGapInventoryResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayOperateResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayOperateRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量日治理控制台入口。统一 POST /api/console/batch-days/operate，转发 orchestrator 内部接口。
 *
 * <p>权限点：高风险动作仅 ROLE_ADMIN 可调用；REOPEN / CLOSE 可考虑后续接审批，本控制器只做转发，状态机和审计在 orchestrator 端。
 *
 * <p>幂等：类级 @Idempotent 防双击重复触发；orchestrator 端的乐观锁兜住状态机 race condition。
 */
@RestController
@Validated
@RequestMapping("/api/console/batch-days")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
@Idempotent
public class ConsoleBatchDayController {

  private final ConsoleOrchestratorPort orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver metadataResolver;

  @GetMapping("/catch-up-preview")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
  public CommonResponse<ConsoleBatchDayCatchupPreviewResponse> previewCatchup(
      @RequestParam String tenantId, @RequestParam String calendarCode) {
    return responseFactory.success(
        orchestratorProxyService.previewBatchDayCatchup(tenantId, calendarCode));
  }

  @GetMapping("/historical-gap-candidates")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
  public CommonResponse<ConsoleBatchDayHistoricalGapInventoryResponse> historicalGapCandidates(
      @RequestParam @NotBlank @Size(max = 64) String tenantId,
      @RequestParam @NotBlank @Size(max = 128) String calendarCode,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate throughDate) {
    return responseFactory.success(orchestratorProxyService.inventoryBatchDayHistoricalGaps(
        tenantId, calendarCode, fromDate, throughDate));
  }

  @PostMapping("/catch-up-control")
  public CommonResponse<ConsoleBatchDayCatchupPreviewResponse> changeCatchupControl(
      @Valid @RequestBody CatchupControlRequest request) {
    ConsoleBatchDayCatchupPreviewResponse preview =
        orchestratorProxyService.changeBatchDayCatchupControl(
            request.tenantId(),
            request.calendarCode(),
            request.action(),
            metadataResolver.current().operatorId(),
            request.reason());
    return responseFactory.success(preview);
  }

  @PostMapping("/operate")
  public CommonResponse<ConsoleBatchDayOperateResponse> operate(
      @Valid @RequestBody BatchDayOperateRequest request) {
    BatchDayOperationCommand command = new BatchDayOperationCommand(
        request.getTenantId(),
        request.getCalendarCode(),
        request.getBizDate(),
        request.getAction(),
        metadataResolver.current().operatorId(),
        request.getReason());
    ConsoleBatchDayOperateResponse result = orchestratorProxyService.batchDayOperate(command);
    return responseFactory.success(result);
  }

  public record CatchupControlRequest(
      @NotBlank @Size(max = 64) String tenantId,
      @NotBlank @Size(max = 128) String calendarCode,
      @NotBlank @Pattern(regexp = "^(PAUSE|RESUME)$") String action,
      @NotBlank @Size(max = 1024) String reason) {}
}
