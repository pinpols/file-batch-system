package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 导出完成阶段：更新文件状态（GENERATED 或 DISPATCHING），并写入审计日志。 */
@Component
public class CompleteStep implements ExportStageStep {

  private static final String KEY_RECORD_COUNT = "recordCount";
  private static final String KEY_OBJECT_NAME = "objectName";

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

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
    if (context == null || context.getAttributes().get(KEY_OBJECT_NAME) == null) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_COMPLETE_INVALID",
          "error.export.complete.invalid",
          new Object[0],
          "objectName missing",
          ERROR_OBJECT_MAPPER);
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
    fileMetadata.put(KEY_RECORD_COUNT, context.getAttributes().get(KEY_RECORD_COUNT));
    fileMetadata.put(KEY_OBJECT_NAME, context.getAttributes().get(KEY_OBJECT_NAME));
    if (context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
      fileMetadata.put(
          "exportSnapshot", context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
    }
    runtimeRepository.updateFileStatus(fileId, nextStatus, fileMetadata);
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put(KEY_RECORD_COUNT, context.getAttributes().get(KEY_RECORD_COUNT));
    detailSummary.put("fileSizeBytes", context.getAttributes().get("fileSizeBytes"));
    detailSummary.put(KEY_OBJECT_NAME, context.getAttributes().get(KEY_OBJECT_NAME));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("EXPORT_COMPLETE")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(String.valueOf(context.getAttributes().get(KEY_OBJECT_NAME)))
            .detailSummary(detailSummary)
            .build());
    return ExportStageResult.success(stage());
  }
}
