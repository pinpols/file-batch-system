package io.github.pinpols.batch.worker.exports.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
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
    // ADR-026: 演练模式不更新 file_record 状态 / 不写 audit。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return ExportStageResult.success(stage());
    }
    if (context == null || context.getAttributes().get(KEY_OBJECT_NAME) == null) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_COMPLETE_INVALID",
          "error.export.complete.invalid",
          new Object[0],
          "objectName missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    ExportPayload exportPayload =
        attrs.get("exportPayload") instanceof ExportPayload payload ? payload : null;
    Long fileId = runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    String nextStatus =
        exportPayload != null && Boolean.TRUE.equals(exportPayload.autoDispatch())
            ? "DISPATCHING"
            : "GENERATED";
    Map<String, Object> fileMetadata = new LinkedHashMap<>();
    fileMetadata.put(KEY_RECORD_COUNT, attrs.get(KEY_RECORD_COUNT));
    fileMetadata.put(KEY_OBJECT_NAME, attrs.get(KEY_OBJECT_NAME));
    if (attrs.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
      fileMetadata.put("exportSnapshot", attrs.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
    }
    runtimeRepository.updateFileStatus(fileId, nextStatus, fileMetadata);
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put(KEY_RECORD_COUNT, attrs.get(KEY_RECORD_COUNT));
    detailSummary.put("fileSizeBytes", attrs.get("fileSizeBytes"));
    detailSummary.put(KEY_OBJECT_NAME, attrs.get(KEY_OBJECT_NAME));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("EXPORT_COMPLETE")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(String.valueOf(attrs.get(KEY_OBJECT_NAME)))
            .detailSummary(detailSummary)
            .build());
    return ExportStageResult.success(stage());
  }
}
