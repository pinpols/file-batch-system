package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.readiness.ReadinessWaitService;
import io.github.pinpols.batch.orchestrator.domain.command.ReadinessWaitHandoverCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trigger → Orchestrator 的 readiness 等待移交入口（{@code X-Internal-Secret} 保护）。
 *
 * <p>Trigger 在 readiness defer 后把原依赖、版本、业务日与截止时间移交过来；Orchestrator 之后自行推进恢复，
 * 不依赖 Quartz 回调。
 */
@RestController
@RequestMapping("/internal/readiness-waits")
@RequiredArgsConstructor
public class ReadinessWaitController {

  private final ReadinessWaitService readinessWaitService;

  @PostMapping
  public ResponseEntity<HandoverResponse> handover(@RequestBody HandoverRequest request) {
    boolean accepted = readinessWaitService.handover(new ReadinessWaitHandoverCommand(
        request.tenantId(),
        request.workflowDefinitionId(),
        request.workflowCode(),
        request.calendarCode(),
        request.scheduleVersion(),
        request.dependsOnJobCodes(),
        request.bizDate(),
        request.scheduledFireTime(),
        request.deferredSince(),
        request.deadline(),
        request.traceId()));
    return ResponseEntity.status(accepted ? HttpStatus.CREATED : HttpStatus.OK)
        .body(new HandoverResponse(accepted));
  }

  @PostMapping("/close")
  public ResponseEntity<CloseResponse> close(@RequestBody CloseRequest request) {
    boolean cancelled = readinessWaitService.cancel(
        request.tenantId(), request.workflowDefinitionId(), request.scheduledFireTime());
    return ResponseEntity.ok(new CloseResponse(cancelled));
  }

  /** 移交请求：原依赖、调度版本、业务日与等待窗口。 */
  public record HandoverRequest(
      String tenantId,
      Long workflowDefinitionId,
      String workflowCode,
      String calendarCode,
      Integer scheduleVersion,
      List<String> dependsOnJobCodes,
      LocalDate bizDate,
      Instant scheduledFireTime,
      Instant deferredSince,
      Instant deadline,
      String traceId) {}

  /** 关闭请求：正常 fire 成功后取消等待。 */
  public record CloseRequest(
      String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {}

  /** 移交结果。 */
  public record HandoverResponse(boolean accepted) {}

  /** 关闭结果。 */
  public record CloseResponse(boolean cancelled) {}
}
