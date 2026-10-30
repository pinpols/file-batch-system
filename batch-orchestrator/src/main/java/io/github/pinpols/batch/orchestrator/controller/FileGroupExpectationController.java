package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService.DefinitionRequest;
import io.github.pinpols.batch.orchestrator.domain.query.FileGroupUploadTarget;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 周期性文件组预期内部配置端点。 */
@RestController
@RequestMapping("/internal/file-group-expectations")
@RequiredArgsConstructor
public class FileGroupExpectationController {

  private final FileGroupExpectationService expectationService;

  @GetMapping("/upload-targets")
  public List<FileGroupUploadTarget> listUploadTargets(@RequestParam String tenantId) {
    return expectationService.listEnabledUploadTargets(tenantId);
  }

  @PutMapping
  public ConfigureResponse configure(@RequestBody ConfigureRequest request) {
    int rows = expectationService.configure(new DefinitionRequest(
        request.tenantId(),
        request.calendarCode(),
        request.workflowDefinitionId(),
        request.fileGroupCode(),
        request.expectedLocalTime(),
        request.dueLocalTime(),
        request.enabled()));
    return new ConfigureResponse(rows == 1, request.fileGroupCode());
  }

  @DeleteMapping
  public boolean deleteDefinition(@RequestBody DeleteRequest request) {
    return expectationService.deleteDefinition(
        request.tenantId(),
        request.calendarCode(),
        request.workflowDefinitionId(),
        request.fileGroupCode());
  }

  public record ConfigureRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String fileGroupCode,
      LocalTime expectedLocalTime,
      LocalTime dueLocalTime,
      boolean enabled) {}

  public record ConfigureResponse(boolean configured, String fileGroupCode) {}

  public record DeleteRequest(
      String tenantId, String calendarCode, Long workflowDefinitionId, String fileGroupCode) {}
}
