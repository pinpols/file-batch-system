package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleUsageSummaryResponse;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleUsageSummaryService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后端操作使用率查询；结果仅用于趋势观察。 */
@RestController
@Validated
@RequestMapping("/api/console/queries")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
@RequiredArgsConstructor
public class ConsoleUsageSummaryController {

  private final ConsoleUsageSummaryService service;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/usage-summary")
  public CommonResponse<List<ConsoleUsageSummaryResponse>> query(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("from") LocalDate from,
      @RequestParam("to") LocalDate to,
      @RequestParam(value = "metricCode", required = false) String metricCode,
      @RequestParam(value = "pageCode", required = false) String pageCode) {
    return responseFactory.success(service.query(tenantId, from, to, metricCode, pageCode).stream()
        .map(ConsoleUsageSummaryResponse::from)
        .toList());
  }
}
