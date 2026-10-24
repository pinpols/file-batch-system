package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService.DefinitionRequest;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService.SatisfactionRequest;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for recurring external-event expectations and post-launch reconciliation. */
@RestController
@RequestMapping("/internal/event-expectations")
@RequiredArgsConstructor
public class ExternalEventExpectationController {

  private final ExternalEventExpectationService expectationService;

  @PutMapping
  public int configure(@RequestBody ConfigureRequest request) {
    DefinitionRequest definition = new DefinitionRequest(
        request.tenantId(),
        request.calendarCode(),
        request.workflowDefinitionId(),
        request.eventCode(),
        request.expectedLocalTime(),
        request.dueLocalTime(),
        request.enabled());
    return expectationService.configure(definition);
  }

  @PostMapping("/satisfied")
  public boolean markSatisfied(@RequestBody MarkSatisfiedRequest request) {
    SatisfactionRequest satisfaction = new SatisfactionRequest(
        request.tenantId(),
        request.calendarCode(),
        request.workflowDefinitionId(),
        request.eventCode(),
        request.bizDate(),
        request.triggerRequestId());
    return expectationService.markSatisfied(satisfaction);
  }

  public record ConfigureRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String eventCode,
      LocalTime expectedLocalTime,
      LocalTime dueLocalTime,
      boolean enabled) {}

  public record MarkSatisfiedRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String eventCode,
      LocalDate bizDate,
      String triggerRequestId) {}
}
