package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.FileGovernanceService;
import com.example.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceScheduler;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件治理内部控制器，基础路径 {@code /internal/files}。 支持对单个文件的归档（{@code archive}）、删除（{@code
 * delete}）、预签名下载（{@code presign}） 和重新分发（{@code redispatch}），以及对文件到达批次组的操作（{@code
 * /arrival-groups/{fileGroupCode}/actions}） 和治理延迟指标查询（{@code GET
 * /governance/latency-metrics}）。仅限内部服务调用。
 */
@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class FileGovernanceController {

  private final FileGovernanceService fileGovernanceService;
  private final FileGovernanceScheduler fileGovernanceScheduler;

  @PostMapping("/{fileId}/archive")
  public FileOperationResponse archive(
      @PathVariable Long fileId, @RequestBody FileOperationRequest request) {
    return new FileOperationResponse(fileGovernanceService.archiveFile(toCommand(fileId, request)));
  }

  @PostMapping("/{fileId}/delete")
  public FileOperationResponse delete(
      @PathVariable Long fileId, @RequestBody FileOperationRequest request) {
    return new FileOperationResponse(fileGovernanceService.deleteFile(toCommand(fileId, request)));
  }

  @PostMapping("/{fileId}/presign")
  public FileDownloadResponse presign(
      @PathVariable Long fileId, @RequestBody FileOperationRequest request) {
    return new FileDownloadResponse(
        fileGovernanceService.presignFileDownload(toCommand(fileId, request)));
  }

  @PostMapping("/{fileId}/redispatch")
  public FileOperationResponse redispatch(
      @PathVariable Long fileId, @RequestBody FileOperationRequest request) {
    return new FileOperationResponse(
        fileGovernanceService.redispatchFile(toCommand(fileId, request)));
  }

  @PostMapping("/arrival-groups/{fileGroupCode}/actions")
  public FileOperationResponse operateArrivalGroup(
      @PathVariable String fileGroupCode, @RequestBody ArrivalGroupOperationRequest request) {
    ArrivalGroupGovernanceCommand command =
        ArrivalGroupGovernanceCommand.builder()
            .tenantId(request.tenantId())
            .fileGroupCode(fileGroupCode)
            .action(request.action())
            .operatorId(request.operatorId())
            .traceId(request.traceId())
            .reason(request.reason())
            .extendWaitSeconds(request.extendWaitSeconds())
            .build();
    String result = fileGovernanceService.operateArrivalGroup(command);
    return new FileOperationResponse(result);
  }

  @GetMapping("/governance/latency-metrics")
  public Map<String, Object> latencyMetrics(@RequestParam String tenantId) {
    return fileGovernanceScheduler.loadLatencyMetrics(tenantId);
  }

  private FileGovernanceCommand toCommand(Long fileId, FileOperationRequest request) {
    return new FileGovernanceCommand(
        request.tenantId(),
        fileId,
        request.channelCode(),
        request.operatorId(),
        request.traceId(),
        request.reason(),
        request.approvalId());
  }

  public record FileOperationRequest(
      String tenantId,
      String channelCode,
      String operatorId,
      String traceId,
      String reason,
      String approvalId) {}

  public record FileOperationResponse(String status) {}

  public record FileDownloadResponse(String downloadUrl) {}

  public record ArrivalGroupOperationRequest(
      String tenantId,
      String action,
      String operatorId,
      String traceId,
      String reason,
      Long extendWaitSeconds) {}
}
