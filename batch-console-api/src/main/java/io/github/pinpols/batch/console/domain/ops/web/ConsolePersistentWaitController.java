package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.query.ConsolePersistentWaitQuery;
import io.github.pinpols.batch.console.application.ops.response.ConsolePersistentWaitPageResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console readiness 与容量准入持久等待只读查询。 */
@RestController
@Validated
@RequestMapping("/api/console/ops/persistent-waits")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
@RequiredArgsConstructor
public class ConsolePersistentWaitController {
  private final ConsoleOrchestratorPort orchestrator;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<ConsolePersistentWaitPageResponse> list(
      @Valid @ModelAttribute PersistentWaitRequest request) {
    ConsolePersistentWaitQuery query = new ConsolePersistentWaitQuery(
        request.tenantId(),
        request.waitType(),
        request.waitStatus(),
        request.workflowDefinitionId(),
        request.bizDateFrom(),
        request.bizDateTo(),
        EmptyChecks.isNull(request.pageNo()) ? 1 : request.pageNo(),
        EmptyChecks.isNull(request.pageSize()) ? 15 : request.pageSize());
    return responseFactory.success(orchestrator.listPersistentWaits(query));
  }

  public record PersistentWaitRequest(
      @NotBlank @Size(max = 64) String tenantId,
      @Pattern(regexp = "^(READINESS|CAPACITY_ADMISSION)$") String waitType,

      @Pattern(regexp = "^(PENDING|RELEASED|TIMED_OUT|CANCELLED|WAITING|RESUMED|REJECTED)$")
      String waitStatus,

      @Positive Long workflowDefinitionId,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateFrom,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateTo,
      @Min(1) Integer pageNo,
      @Min(1) @Max(100) Integer pageSize) {}
}
