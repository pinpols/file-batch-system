package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplayCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplayPreviewResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplayPreviewService;
import io.github.pinpols.batch.orchestrator.controller.response.ReplayOperationResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal control-plane preview of the complete frozen source Workflow graph. */
@RestController
@RequestMapping("/internal/orchestrator/workflow-replay")
@RequiredArgsConstructor
public class WorkflowRunReplayController {

  private final WorkflowRunReplayPreviewService previewService;

  @PostMapping("/preview")
  public CommonResponse<WorkflowRunReplayPreviewResponse> preview(
      @RequestBody WorkflowRunReplayCommand command) {
    WorkflowRunReplayPreviewResponse preview = previewService.preview(command);
    return CommonResponse.success(preview);
  }

  @PostMapping("/submit")
  public CommonResponse<ReplayOperationResponse> submitForApproval(
      @RequestBody WorkflowRunReplayCommand command) {
    ReplayOperationEntity operation = previewService.submitForApproval(command);
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  @PostMapping("/operations/{operationId}/reject")
  public CommonResponse<ReplayOperationResponse> reject(
      @PathVariable Long operationId, @RequestBody ReplayOperationRejectRequest request) {
    ReplayOperationEntity operation = previewService.reject(request.tenantId(), operationId);
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  @PostMapping("/operations/{operationId}/approve")
  public CommonResponse<ReplayOperationResponse> approve(
      @PathVariable Long operationId, @RequestBody ReplayOperationApproveRequest request) {
    ReplayOperationEntity operation =
        previewService.approve(request.tenantId(), operationId, request.approver());
    ReplayOperationResponse response = ReplayOperationResponse.from(operation);
    return CommonResponse.success(response);
  }

  public record ReplayOperationRejectRequest(String tenantId) {}

  public record ReplayOperationApproveRequest(String tenantId, String approver) {}
}
