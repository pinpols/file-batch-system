package io.github.pinpols.batch.console.domain.file.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.realtime.ConsoleProgressSnapshotPublisher;
import io.github.pinpols.batch.console.domain.file.application.progress.PipelineProgressQueryService;
import io.github.pinpols.batch.console.domain.file.application.progress.PipelineProgressResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/console/pipeline-progress")
@PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
public class ConsolePipelineExecutionController {
  private final PipelineProgressQueryService queries;
  private final ConsoleProgressSnapshotPublisher push;
  private final ConsoleResponseFactory responses;

  @GetMapping
  public CommonResponse<PipelineProgressResponse> query(
      @RequestParam String tenantId, @RequestParam List<Long> pipelineInstanceIds) {
    return responses.success(queries.query(tenantId, pipelineInstanceIds));
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @RequestParam String tenantId, @RequestParam List<Long> pipelineInstanceIds) {
    List<Long> ids = PipelineProgressQueryService.normalize(pipelineInstanceIds);
    PipelineProgressResponse initial = queries.query(tenantId, ids);
    String stream =
        "pipeline-progress-" + ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    return push.subscribe(
        initial.tenantId(),
        stream,
        "pipeline-progress-snapshot",
        initial,
        () -> queries.queryAuthorized(initial.tenantId(), ids),
        PipelineProgressResponse::pipelines);
  }
}
