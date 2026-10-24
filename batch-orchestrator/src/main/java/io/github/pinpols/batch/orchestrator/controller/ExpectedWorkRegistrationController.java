package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.batchday.ExpectedWorkRegistrationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExpectedWorkRegistrationService.FileGroupExpectation;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExpectedWorkRegistrationService.RegistrationResult;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal idempotent ingress for upstream file manifests that declare expected groups in advance. */
@RestController
@RequestMapping("/internal/expected-work-occurrences")
@RequiredArgsConstructor
public class ExpectedWorkRegistrationController {

  private final ExpectedWorkRegistrationService registrationService;

  @PostMapping("/file-groups")
  public RegistrationResult registerFileGroup(@RequestBody RegisterFileGroupRequest request) {
    FileGroupExpectation expectation = new FileGroupExpectation(
        request.tenantId(),
        request.calendarCode(),
        request.workflowDefinitionId(),
        request.fileGroupCode(),
        request.bizDate(),
        request.expectedAt(),
        request.dueAt());
    return registrationService.register(expectation);
  }

  public record RegisterFileGroupRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String fileGroupCode,
      LocalDate bizDate,
      Instant expectedAt,
      Instant dueAt) {}
}
