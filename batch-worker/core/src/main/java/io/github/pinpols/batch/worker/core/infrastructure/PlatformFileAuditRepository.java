package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.FILE_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.PIPELINE_INSTANCE_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.defaultText;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toLong;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.truncate;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/** 文件错误记录与审计日志的数据访问协作者。 */
@RequiredArgsConstructor
final class PlatformFileAuditRepository {

  private final PlatformFileRuntimeMapper mapper;

  Long insertFileErrorRecord(FileErrorRecordParam param) {
    Map<String, Object> values =
        params(
            TENANT_ID,
            param.getTenantId(),
            FILE_ID,
            param.getFileId(),
            PIPELINE_INSTANCE_ID,
            param.getPipelineInstanceId(),
            "pipelineStepRunId",
            param.getPipelineStepRunId(),
            "recordNo",
            param.getRecordNo(),
            "errorCode",
            param.getErrorCode(),
            "errorMessage",
            truncate(param.getErrorMessage(), 1024),
            "errorStage",
            param.getErrorStage(),
            "isSkipped",
            param.isSkipped(),
            "skipAction",
            param.getSkipAction(),
            "rawRecordJson",
            toJson(param.getRawRecord()),
            "sourceRowNum",
            param.getSourceRowNum(),
            "sourceColumn",
            truncate(param.getSourceColumn(), 256));
    mapper.insertFileErrorRecord(values);
    return toLong(values.get(ID));
  }

  List<Map<String, Object>> loadFileErrorRecords(
      String tenantId, Long fileId, String errorCode, String errorStage, int limit) {
    if (!Texts.hasText(tenantId) || limit <= 0) {
      return List.of();
    }
    return mapper.selectFileErrorRecords(
        params(
            TENANT_ID,
            tenantId,
            FILE_ID,
            fileId,
            "errorCode",
            errorCode,
            "errorStage",
            errorStage,
            "limit",
            limit));
  }

  void appendAudit(FileAuditParam param) {
    if (param.getFileId() == null
        || !Texts.hasText(param.getTenantId())
        || !Texts.hasText(param.getOperationType())
        || !Texts.hasText(param.getOperationResult())) {
      return;
    }
    mapper.insertFileAuditLog(
        params(
            TENANT_ID,
            param.getTenantId(),
            FILE_ID,
            param.getFileId(),
            "operationType",
            param.getOperationType(),
            "operationResult",
            param.getOperationResult(),
            "operatorType",
            defaultText(param.getOperatorType(), "SYSTEM"),
            "operatorId",
            param.getOperatorId(),
            "traceId",
            param.getTraceId(),
            "evidenceRef",
            param.getEvidenceRef(),
            "detailSummaryJson",
            toJson(param.getDetailSummary())));
  }
}
