package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.defaultText;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.truncate;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.param.PlatformFileAuditInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformFileErrorInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 文件错误记录与审计日志的数据访问协作者。 */
@Repository
@RequiredArgsConstructor
public class PlatformFileAuditRepository {

  private final PlatformFileRuntimeMapper mapper;

  public Long insertFileErrorRecord(FileErrorRecordParam param) {
    String tenantId = param.getTenantId();
    Long fileId = param.getFileId();
    Long pipelineInstanceId = param.getPipelineInstanceId();
    Long pipelineStepRunId = param.getPipelineStepRunId();
    Long recordNo = param.getRecordNo();
    String errorCode = param.getErrorCode();
    String errorMessage = truncate(param.getErrorMessage(), 1024);
    String errorStage = param.getErrorStage();
    boolean skipped = param.isSkipped();
    String skipAction = param.getSkipAction();
    String rawRecordJson = toJson(param.getRawRecord());
    Long sourceRowNum = param.getSourceRowNum();
    String sourceColumn = truncate(param.getSourceColumn(), 256);
    PlatformFileErrorInsertParam values = PlatformFileErrorInsertParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .pipelineInstanceId(pipelineInstanceId)
        .pipelineStepRunId(pipelineStepRunId)
        .recordNo(recordNo)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .errorStage(errorStage)
        .isSkipped(skipped)
        .skipAction(skipAction)
        .rawRecordJson(rawRecordJson)
        .sourceRowNum(sourceRowNum)
        .sourceColumn(sourceColumn)
        .build();
    mapper.insertFileErrorRecord(values);
    return values.getId();
  }

  public void appendAudit(FileAuditParam param) {
    boolean fileAuditParamIsIncomplete = param.getFileId() == null
        || !Texts.hasText(param.getTenantId())
        || !Texts.hasText(param.getOperationType())
        || !Texts.hasText(param.getOperationResult());
    if (fileAuditParamIsIncomplete) {
      return;
    }
    String tenantId = param.getTenantId();
    Long fileId = param.getFileId();
    String operationType = param.getOperationType();
    String operationResult = param.getOperationResult();
    String operatorType = defaultText(param.getOperatorType(), "SYSTEM");
    String operatorId = param.getOperatorId();
    String traceId = param.getTraceId();
    String evidenceRef = param.getEvidenceRef();
    String detailSummaryJson = toJson(param.getDetailSummary());
    PlatformFileAuditInsertParam statementParams = PlatformFileAuditInsertParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .operationType(operationType)
        .operationResult(operationResult)
        .operatorType(operatorType)
        .operatorId(operatorId)
        .traceId(traceId)
        .evidenceRef(evidenceRef)
        .detailSummaryJson(detailSummaryJson)
        .build();
    mapper.insertFileAuditLog(statementParams);
  }
}
