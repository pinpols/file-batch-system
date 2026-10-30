package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.realtime.ConsoleProgressSnapshotPublisher;
import io.github.pinpols.batch.console.domain.job.application.JobExecutionQueryService;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.constraints.Positive;
import java.util.Arrays;
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
@RequestMapping("/api/console/job-instances")
@PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
public class ConsoleJobExecutionController {
  private final JobExecutionQueryService queries;
  private final ConsoleProgressSnapshotPublisher push;
  private final ConsoleResponseFactory responses;

  @GetMapping("/{id}/execution-progress")
  public CommonResponse<JobExecutionProgressResponse> query(
      @PathVariable @Positive Long id, @RequestParam String tenantId) {
    return responses.success(queries.query(tenantId, id));
  }

  @GetMapping(
      value = "/{id}/execution-progress/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable @Positive Long id, @RequestParam String tenantId) {
    JobExecutionProgressResponse initial = queries.query(tenantId, id);
    return push.subscribe(
        initial.tenantId(),
        "job-execution-" + id,
        "job-execution-progress",
        initial,
        () -> queries.queryAuthorized(initial.tenantId(), id),
        snapshot -> Arrays.asList(
            snapshot.instanceStatus(),
            snapshot.startedAt(),
            snapshot.finishedAt(),
            snapshot.expectedPartitions(),
            snapshot.workflowRunId(),
            snapshot.nodeCode(),
            snapshot.pipelineInstanceId(),
            snapshot.pipelineStatus(),
            snapshot.currentStage(),
            snapshot.systemPipelineCode(),
            snapshot.systemPipelineVersion(),
            snapshot.stageGraphAvailable(),
            snapshot.counts(),
            snapshot.stages()));
  }
}
