package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.query.ConsoleWaitRelationshipQuery;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWaitRelationshipPage;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/ops/wait-relationships")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
@RequiredArgsConstructor
public class ConsoleWaitRelationshipController {
  private final ConsoleOrchestratorPort orchestrator;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<ConsoleWaitRelationshipPage> list(
      @Valid @ModelAttribute ConsoleWaitRelationshipQuery query) {
    return responseFactory.success(orchestrator.listWaitRelationships(query));
  }
}
