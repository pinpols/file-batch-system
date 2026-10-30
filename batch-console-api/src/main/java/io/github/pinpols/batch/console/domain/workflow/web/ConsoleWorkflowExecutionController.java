package io.github.pinpols.batch.console.domain.workflow.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowExecutionPushService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowExecutionQueryService;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/console/workflow-runs")
@PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
public class ConsoleWorkflowExecutionController {
  private final WorkflowExecutionQueryService queryService;
  private final ConsoleResponseFactory responseFactory;
  private final WorkflowExecutionPushService pushService;

  @GetMapping(
      value = "/{id}/execution-progress/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable @Positive Long id, @RequestParam String tenantId) {
    return pushService.subscribe(tenantId, id);
  }

  @GetMapping("/{id}/execution-progress")
  public CommonResponse<WorkflowExecutionProgressResponse> progress(
      @PathVariable @Positive Long id, @RequestParam String tenantId) {
    return responseFactory.success(queryService.progress(tenantId, id));
  }
}
