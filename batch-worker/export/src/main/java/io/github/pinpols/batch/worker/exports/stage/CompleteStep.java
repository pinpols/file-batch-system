package io.github.pinpols.batch.worker.exports.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileAuditOperationType;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.OperationResult;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
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

  private static final String KEY_RECORD_COUNT = PipelineRuntimeKeys.RECORD_COUNT;
  private static final String KEY_OBJECT_NAME = PipelineRuntimeKeys.OBJECT_NAME;

  private static final ObjectMapper ERROR_OBJECT_MAPPER = JsonUtils.newDefaultMapper();

  private final PlatformFileRecordRepository fileRecords;
  private final PlatformFileAuditRepository fileAudits;

  public CompleteStep(
      PlatformFileRecordRepository fileRecords, PlatformFileAuditRepository fileAudits) {
    this.fileRecords = fileRecords;
    this.fileAudits = fileAudits;
  }

  @Override
  public ExportStage stage() {
    return ExportStage.COMPLETE;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    // ADR-026: 演练模式不更新 file_record 状态 / 不写 audit。
    Map<String, Object> dryRunAttributes = EmptyChecks.isNull(context) ? null : context.getAttributes();
    boolean dryRun = DryRunGuard.fromAttributes(dryRunAttributes).isDryRun();
    if (dryRun) {
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
        attrs.get(ExportRuntimeKeys.EXPORT_PAYLOAD) instanceof ExportPayload payload
            ? payload
            : null;
    Object fileIdValue = attrs.get(PipelineRuntimeKeys.FILE_ID);
    Long fileId = PlatformRuntimeValues.toLong(fileIdValue);
    String nextStatus = exportPayload != null && Boolean.TRUE.equals(exportPayload.autoDispatch())
        ? FileStatus.DISPATCHING.code()
        : FileStatus.GENERATED.code();
    Map<String, Object> fileMetadata = new LinkedHashMap<>();
    fileMetadata.put(KEY_RECORD_COUNT, attrs.get(KEY_RECORD_COUNT));
    fileMetadata.put(KEY_OBJECT_NAME, attrs.get(KEY_OBJECT_NAME));
    if (attrs.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
      fileMetadata.put(
          PipelineRuntimeKeys.EXPORT_SNAPSHOT, attrs.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
    }
    fileRecords.updateFileStatus(fileId, nextStatus, fileMetadata);
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put(KEY_RECORD_COUNT, attrs.get(KEY_RECORD_COUNT));
    detailSummary.put(
        PipelineRuntimeKeys.FILE_SIZE_BYTES, attrs.get(PipelineRuntimeKeys.FILE_SIZE_BYTES));
    detailSummary.put(KEY_OBJECT_NAME, attrs.get(KEY_OBJECT_NAME));
    Object traceIdValue = attrs.get(PipelineRuntimeKeys.TRACE_ID);
    String traceId = String.valueOf(traceIdValue);
    Object objectNameValue = attrs.get(KEY_OBJECT_NAME);
    String evidenceRef = String.valueOf(objectNameValue);
    fileAudits.appendAudit(FileAuditParam.builder()
        .fileId(fileId)
        .tenantId(context.getTenantId())
        .operationType(FileAuditOperationType.EXPORT_COMPLETE.code())
        .operationResult(OperationResult.SUCCESS.code())
        .operatorType(AuditLogConstants.OPERATOR_TYPE_SYSTEM)
        .operatorId(context.getWorkerId())
        .traceId(traceId)
        .evidenceRef(evidenceRef)
        .detailSummary(detailSummary)
        .build());
    return ExportStageResult.success(stage());
  }
}
