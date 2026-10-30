package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.contract.response.CalendarDependencyWaitResponse;
import io.github.pinpols.batch.orchestrator.application.service.calendar.CalendarDependencyWaitGovernanceService;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 供 Console 调用的租户隔离内部端点；所有决策仍由 Orchestrator 负责。 */
@RestController
@RequestMapping("/internal/calendar-dependency-waits")
@RequiredArgsConstructor
public class CalendarDependencyWaitController {

  private final CalendarDependencyWaitGovernanceService governanceService;

  @GetMapping
  public List<CalendarDependencyWaitResponse> list(
      @RequestParam String tenantId,
      @RequestParam(required = false) String calendarCode,
      @RequestParam(required = false) String waitStatus,
      @RequestParam(defaultValue = "100") int limit) {
    List<CalendarDependencyWaitEntity> waits =
        governanceService.list(tenantId, calendarCode, waitStatus, limit);
    return waits.stream().map(CalendarDependencyWaitResponse::from).toList();
  }

  @PostMapping("/manual-action")
  public CalendarDependencyWaitResponse requestManualAction(
      @RequestBody ManualActionRequest request) {
    CalendarDependencyWaitEntity wait = governanceService.requestManualAction(
        new CalendarDependencyWaitGovernanceService.ManualActionCommand(
            request.tenantId(),
            request.calendarCode(),
            request.bizDate(),
            request.action(),
            request.operatorId(),
            request.reason()));
    return CalendarDependencyWaitResponse.from(wait);
  }

  public record ManualActionRequest(
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String action,
      String operatorId,
      String reason) {}
}
