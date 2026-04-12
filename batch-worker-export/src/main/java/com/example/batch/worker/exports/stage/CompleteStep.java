package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 导出完成阶段：更新文件状态（GENERATED 或 DISPATCHING），并写入审计日志。 */
@Component
public class CompleteStep implements ExportStageStep {

  private final PlatformFileRuntimeRepository runtimeRepository;

  public CompleteStep(PlatformFileRuntimeRepository runtimeRepository) {
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public ExportStage stage() {
    return ExportStage.COMPLETE;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    if (context == null || context.getAttributes().get("objectName") == null) {
      return ExportStageResult.failure(stage(), "EXPORT_COMPLETE_INVALID", "objectName missing");
    }
    ExportPayload exportPayload =
        context.getAttributes().get("exportPayload") instanceof ExportPayload payload
            ? payload
            : null;
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    String nextStatus =
        exportPayload != null && Boolean.TRUE.equals(exportPayload.autoDispatch())
            ? "DISPATCHING"
            : "GENERATED";
    Map<String, Object> fileMetadata = new LinkedHashMap<>();
    fileMetadata.put("recordCount", context.getAttributes().get("recordCount"));
    fileMetadata.put("objectName", context.getAttributes().get("objectName"));
    if (context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
      fileMetadata.put(
          "exportSnapshot", context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
    }
    runtimeRepository.updateFileStatus(fileId, nextStatus, fileMetadata);
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put("recordCount", context.getAttributes().get("recordCount"));
    detailSummary.put("fileSizeBytes", context.getAttributes().get("fileSizeBytes"));
    detailSummary.put("objectName", context.getAttributes().get("objectName"));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("EXPORT_COMPLETE")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(String.valueOf(context.getAttributes().get("objectName")))
            .detailSummary(detailSummary)
            .build());
    return ExportStageResult.success(stage());
  }
}
