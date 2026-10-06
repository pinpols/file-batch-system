package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.enums.FileAuditOperationType;
import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.OperationResult;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 分发完成阶段：汇总回执状态并写入审计日志，作为整个分发 pipeline 的终态步骤。 */
@Component
public class CompleteDispatchStep implements DispatchStageStep {

  private final PlatformFileRecordRepository fileRecords;
  private final PlatformFileAuditRepository fileAudits;

  public CompleteDispatchStep(
      PlatformFileRecordRepository fileRecords, PlatformFileAuditRepository fileAudits) {
    this.fileRecords = fileRecords;
    this.fileAudits = fileAudits;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.COMPLETE;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不更新 file_record 状态 / 不写 audit。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes())
        .isDryRun()) {
      return DispatchStageResult.success(stage());
    }
    Object payload = EmptyChecks.isNull(context)
        ? null
        : context.getAttributes().get(DispatchRuntimeKeys.DISPATCH_PAYLOAD);
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_COMPLETE_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    Long fileId = PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    String receiptStatus = String.valueOf(
        attrs.getOrDefault(DispatchRuntimeKeys.RECEIPT_STATUS, FileReceiptStatus.NONE.code()));
    if (FileReceiptStatus.SUCCESS.code().equalsIgnoreCase(receiptStatus)) {
      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put(DispatchRuntimeKeys.CHANNEL_CODE, dispatchPayload.channelCode());
      if (EmptyChecks.isNotNull(attrs.get(DispatchRuntimeKeys.RECEIPT_CODE))) {
        fileMetadata.put(
            DispatchRuntimeKeys.RECEIPT_CODE, attrs.get(DispatchRuntimeKeys.RECEIPT_CODE));
      }
      fileRecords.updateFileStatus(fileId, FileStatus.DISPATCHED.code(), fileMetadata);
    }
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put(DispatchRuntimeKeys.CHANNEL_CODE, dispatchPayload.channelCode());
    detailSummary.put(DispatchRuntimeKeys.DISPATCH_TARGET, dispatchPayload.dispatchTarget());
    detailSummary.put(
        DispatchRuntimeKeys.EXTERNAL_REQUEST_ID,
        attrs.get(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID));
    detailSummary.put(
        DispatchRuntimeKeys.RECEIPT_CODE, attrs.get(DispatchRuntimeKeys.RECEIPT_CODE));
    detailSummary.put(DispatchRuntimeKeys.RECEIPT_STATUS, receiptStatus);
    fileAudits.appendAudit(FileAuditParam.builder()
        .fileId(fileId)
        .tenantId(context.getTenantId())
        .operationType(FileAuditOperationType.DISPATCH_COMPLETE.code())
        .operationResult(OperationResult.SUCCESS.code())
        .operatorType(AuditLogConstants.OPERATOR_TYPE_SYSTEM)
        .operatorId(context.getWorkerId())
        .traceId(String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID)))
        .evidenceRef(
            String.valueOf(attrs.getOrDefault(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "")))
        .detailSummary(detailSummary)
        .build());
    return DispatchStageResult.success(stage());
  }
}
