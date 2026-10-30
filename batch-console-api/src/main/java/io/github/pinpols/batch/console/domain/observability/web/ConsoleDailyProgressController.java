package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.realtime.ConsoleProgressSnapshotPublisher;
import io.github.pinpols.batch.console.domain.observability.application.progress.DailyProgressQueryService;
import io.github.pinpols.batch.console.domain.observability.application.progress.DailyProgressResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/console/dashboard/daily-progress")
@PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
public class ConsoleDailyProgressController {
  private final DailyProgressQueryService queries;
  private final ConsoleProgressSnapshotPublisher push;
  private final ConsoleResponseFactory responses;

  @GetMapping
  public CommonResponse<DailyProgressResponse> query(
      @RequestParam String tenantId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
    return responses.success(queries.query(tenantId, bizDate));
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @RequestParam String tenantId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
    DailyProgressResponse initial = queries.query(tenantId, bizDate);
    return push.subscribe(
        initial.tenantId(),
        "daily-progress-" + bizDate,
        "daily-progress",
        initial,
        () -> queries.queryAuthorized(initial.tenantId(), bizDate),
        snapshot -> List.of(snapshot.counts(), snapshot.coverage()));
  }
}
