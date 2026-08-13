package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.governance.FileGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.FileUploadSessionResponse;
import io.github.pinpols.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileUploadSessionCommand;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceLatencyMetrics;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceScheduler;
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

  @PostMapping("/presign-upload")
  public FileUploadSessionResponse presignUpload(@RequestBody FileUploadRequest request) {
    return fileGovernanceService.createUploadSession(FileUploadSessionCommand.builder()
        .tenantId(request.tenantId())
        .channelCode(request.channelCode())
        .fileName(request.fileName())
        .operatorId(request.operatorId())
        .traceId(request.traceId())
        .build());
  }

  @PostMapping("/{fileId}/confirm-arrival")
  public FileOperationResponse confirmArrival(
      @PathVariable Long fileId, @RequestBody FileOperationRequest request) {
    return new FileOperationResponse(
        fileGovernanceService.confirmFileArrival(toCommand(fileId, request)));
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
    ArrivalGroupGovernanceCommand command = toArrivalGroupCommand(fileGroupCode, request);
    String result = fileGovernanceService.operateArrivalGroup(command);
    return new FileOperationResponse(result);
  }

  @GetMapping("/governance/latency-metrics")
  public FileGovernanceLatencyMetrics latencyMetrics(@RequestParam String tenantId) {
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

  /**
   * 将 HTTP 请求转换为业务命令，集中保留路径参数与请求体字段的合并规则。
   *
   * <p>文件组编码来自 URL，其他字段来自请求体；单独命名这个边界转换可以让 Controller 方法只保留“接收、调用、返回”的主流程，
   * 也避免后续新增字段时把参数拼装逻辑和业务调用混在一起。
   */
  private ArrivalGroupGovernanceCommand toArrivalGroupCommand(
      String fileGroupCode, ArrivalGroupOperationRequest request) {
    return ArrivalGroupGovernanceCommand.builder()
        .tenantId(request.tenantId())
        .fileGroupCode(fileGroupCode)
        .bizDate(request.bizDate())
        .action(request.action())
        .operatorId(request.operatorId())
        .traceId(request.traceId())
        .reason(request.reason())
        .extendWaitSeconds(request.extendWaitSeconds())
        .build();
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

  public record FileUploadRequest(
      String tenantId, String channelCode, String fileName, String operatorId, String traceId) {}

  public record ArrivalGroupOperationRequest(
      String tenantId,
      String bizDate,
      String action,
      String operatorId,
      String traceId,
      String reason,
      Long extendWaitSeconds) {}
}
