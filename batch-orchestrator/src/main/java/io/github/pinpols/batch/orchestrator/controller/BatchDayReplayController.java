package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayPreviewResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeOperationService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.ReplayOperationPreviewResponse;
import io.github.pinpols.batch.orchestrator.controller.response.BatchDayReplayEntryResponse;
import io.github.pinpols.batch.orchestrator.controller.response.BatchDayReplaySessionResponse;
import io.github.pinpols.batch.orchestrator.controller.response.ReplayOperationResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
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
  private final BatchDayReplayRangeOperationService rangeOperationService;

  @PostMapping("/operations/date-range/preview")
  public CommonResponse<ReplayOperationPreviewResponse> previewDateRange(
      @RequestBody BatchDayReplayRangeCommand command) {
    ReplayOperationPreviewResponse preview = rangeOperationService.preview(command);
    return CommonResponse.success(preview);
  }

  @PostMapping("/operations/date-range")
  public CommonResponse<ReplayOperationResponse> submitDateRange(
      @RequestBody BatchDayReplayRangeCommand command) {
    ReplayOperationEntity operation = rangeOperationService.submit(command);
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  @GetMapping("/operations")
  public CommonResponse<List<ReplayOperationResponse>> listOperations(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    List<ReplayOperationEntity> operations = rangeOperationService.list(tenantId, limit);
    List<ReplayOperationResponse> responses =
        operations.stream().map(ReplayOperationResponse::from).toList();
    return CommonResponse.success(responses);
  }

  @GetMapping("/operations/{operationId}")
  public CommonResponse<ReplayOperationResponse> operationDetail(
      @PathVariable("operationId") Long operationId, @RequestParam("tenantId") String tenantId) {
    ReplayOperationEntity operation = rangeOperationService.get(tenantId, operationId);
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  @GetMapping("/operations/{operationId}/sessions")
  public CommonResponse<List<BatchDayReplaySessionResponse>> operationSessions(
      @PathVariable("operationId") Long operationId, @RequestParam("tenantId") String tenantId) {
    List<BatchDayReplaySessionEntity> sessionEntities =
        rangeOperationService.sessions(tenantId, operationId);
    List<BatchDayReplaySessionResponse> responses =
        sessionEntities.stream().map(BatchDayReplaySessionResponse::from).toList();
    return CommonResponse.success(responses);
  }

  @PostMapping("/operations/{operationId}/approve")
  public CommonResponse<ReplayOperationResponse> approveOperation(
      @PathVariable("operationId") Long operationId,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("approver") String approver) {
    ReplayOperationEntity operation =
        rangeOperationService.approve(tenantId, operationId, approver);
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  @PostMapping("/operations/{operationId}/cancel")
  public CommonResponse<ReplayOperationResponse> cancelOperation(
      @PathVariable("operationId") Long operationId, @RequestParam("tenantId") String tenantId) {
    ReplayOperationEntity operation = rangeOperationService.cancel(tenantId, operationId);
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  @PostMapping("/sessions")
  public CommonResponse<BatchDayReplaySessionResponse> submit(
      @RequestBody BatchDayReplaySubmitCommand command) {
    BatchDayReplaySessionEntity entity = replayService.submit(command);
    BatchDayReplaySessionResponse response = BatchDayReplaySessionResponse.from(entity);
    return CommonResponse.success(response);
  }

  @PostMapping("/sessions/preview")
  public CommonResponse<BatchDayReplayPreviewResponse> preview(
      @RequestBody BatchDayReplaySubmitCommand command) {
    BatchDayReplayPreviewResponse preview = replayService.preview(command);
    return CommonResponse.success(preview);
  }

  @GetMapping("/sessions")
  public CommonResponse<List<BatchDayReplaySessionResponse>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    List<BatchDayReplaySessionEntity> sessions =
        replayService.listSessions(tenantId, status, limit);
    List<BatchDayReplaySessionResponse> responses =
        sessions.stream().map(BatchDayReplaySessionResponse::from).toList();
    return CommonResponse.success(responses);
  }

  @PostMapping("/sessions/{sessionId}/approve")
  public CommonResponse<BatchDayReplaySessionResponse> approve(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("approver") String approver) {
    BatchDayReplaySessionEntity entity = replayService.approve(tenantId, sessionId, approver);
    BatchDayReplaySessionResponse response = BatchDayReplaySessionResponse.from(entity);
    return CommonResponse.success(response);
  }

  @PostMapping("/sessions/{sessionId}/cancel")
  public CommonResponse<BatchDayReplaySessionResponse> cancel(
      @PathVariable("sessionId") Long sessionId, @RequestParam("tenantId") String tenantId) {
    BatchDayReplaySessionEntity entity = replayService.cancel(tenantId, sessionId);
    BatchDayReplaySessionResponse response = BatchDayReplaySessionResponse.from(entity);
    return CommonResponse.success(response);
  }

  @GetMapping("/sessions/{sessionId}")
  public CommonResponse<BatchDayReplaySessionResponse> detail(
      @PathVariable("sessionId") Long sessionId, @RequestParam("tenantId") String tenantId) {
    BatchDayReplaySessionEntity entity = replayService.getSession(tenantId, sessionId);
    BatchDayReplaySessionResponse response = BatchDayReplaySessionResponse.from(entity);
    return CommonResponse.success(response);
  }

  /** entries 进度查询 — 给 UI 展示每条 instance / version 的执行状态。 */
  @GetMapping("/sessions/{sessionId}/entries")
  public CommonResponse<List<BatchDayReplayEntryResponse>> entries(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "500") int limit) {
    List<BatchDayReplayEntryEntity> entries =
        replayService.listEntries(tenantId, sessionId, status, limit);
    List<BatchDayReplayEntryResponse> responses =
        entries.stream().map(BatchDayReplayEntryResponse::from).toList();
    return CommonResponse.success(responses);
  }

  @PostMapping("/sessions/{sessionId}/entries/{entryId}/retry-submission")
  public CommonResponse<BatchDayReplaySessionResponse> retryFailedSubmission(
      @PathVariable("sessionId") Long sessionId,
      @PathVariable("entryId") Long entryId,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("operatorId") String operatorId,
      @RequestParam("reason") String reason) {
    BatchDayReplaySessionEntity entity =
        replayService.retryFailedSubmission(tenantId, sessionId, entryId, operatorId, reason);
    BatchDayReplaySessionResponse response = BatchDayReplaySessionResponse.from(entity);
    return CommonResponse.success(response);
  }
}
