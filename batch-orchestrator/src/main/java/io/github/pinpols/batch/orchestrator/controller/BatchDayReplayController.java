package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayPreviewResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-020 批次日重放内部控制器：{@code /internal/orchestrator/batch-day-replay}
 *
 * <p>console-api 通过 ConsoleOrchestratorProxyService 转发；UI 端 5 个核心动作 = submit / approve / cancel /
 * detail / progress（entries 列表）。
 */
@RestController
@RequestMapping("/internal/orchestrator/batch-day-replay")
@RequiredArgsConstructor
public class BatchDayReplayController {

  private final BatchDayReplayService replayService;

  @PostMapping("/sessions")
  public CommonResponse<BatchDayReplaySessionEntity> submit(
      @RequestBody BatchDayReplaySubmitCommand command) {
    return CommonResponse.success(replayService.submit(command));
  }

  @PostMapping("/sessions/preview")
  public CommonResponse<BatchDayReplayPreviewResponse> preview(
      @RequestBody BatchDayReplaySubmitCommand command) {
    return CommonResponse.success(replayService.preview(command));
  }

  @PostMapping("/sessions/{sessionId}/approve")
  public CommonResponse<BatchDayReplaySessionEntity> approve(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("approver") String approver) {
    return CommonResponse.success(replayService.approve(tenantId, sessionId, approver));
  }

  @PostMapping("/sessions/{sessionId}/cancel")
  public CommonResponse<BatchDayReplaySessionEntity> cancel(
      @PathVariable("sessionId") Long sessionId, @RequestParam("tenantId") String tenantId) {
    return CommonResponse.success(replayService.cancel(tenantId, sessionId));
  }

  @GetMapping("/sessions/{sessionId}")
  public CommonResponse<BatchDayReplaySessionEntity> detail(
      @PathVariable("sessionId") Long sessionId, @RequestParam("tenantId") String tenantId) {
    return CommonResponse.success(replayService.getSession(tenantId, sessionId));
  }

  /** entries 进度查询 — 给 UI 展示每条 instance / version 的执行状态。 */
  @GetMapping("/sessions/{sessionId}/entries")
  public CommonResponse<List<BatchDayReplayEntryEntity>> entries(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "500") int limit) {
    return CommonResponse.success(replayService.listEntries(sessionId, status, limit));
  }
}
