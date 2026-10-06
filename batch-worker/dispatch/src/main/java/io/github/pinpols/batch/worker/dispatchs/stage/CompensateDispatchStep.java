package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.enums.FileAuditOperationType;
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
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 分发补偿阶段：在投递彻底失败后将分发记录标记为 COMPENSATED，并写入审计日志。 */
@Component
public class CompensateDispatchStep implements DispatchStageStep {

  private final FileDispatchRepository fileDispatchRepository;
  private final PlatformFileRecordRepository fileRecords;
  private final PlatformFileAuditRepository fileAudits;

  public CompensateDispatchStep(
      FileDispatchRepository fileDispatchRepository,
      PlatformFileRecordRepository fileRecords,
      PlatformFileAuditRepository fileAudits) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.fileRecords = fileRecords;
    this.fileAudits = fileAudits;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.COMPENSATE;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不发外部补偿（也没真投递过），跳过即可。
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
          "DISPATCH_COMPENSATE_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    Long fileId = PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    int updated = fileDispatchRepository.markCompensated(
        context.getTenantId(),
        fileId,
        dispatchPayload.channelCode(),
        "DISPATCH_COMPENSATED",
        "compensated");
    if (updated <= 0) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_COMPENSATE_FAILED",
          "error.dispatch.compensate.failed",
          new Object[0],
          "failed to mark compensated",
          ERROR_OBJECT_MAPPER);
    }
    fileRecords.updateFileStatus(
        fileId,
        FileStatus.FAILED.code(),
        Map.of(
            DispatchRuntimeKeys.CHANNEL_CODE,
            Objects.requireNonNullElse(dispatchPayload.channelCode(), "")));
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put(DispatchRuntimeKeys.CHANNEL_CODE, dispatchPayload.channelCode());
    detailSummary.put(DispatchRuntimeKeys.DISPATCH_TARGET, dispatchPayload.dispatchTarget());
    detailSummary.put(
        DispatchRuntimeKeys.EXTERNAL_REQUEST_ID,
        attrs.get(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID));
    fileAudits.appendAudit(FileAuditParam.builder()
        .fileId(fileId)
        .tenantId(context.getTenantId())
        .operationType(FileAuditOperationType.DISPATCH_COMPENSATE.code())
        .operationResult(OperationResult.FAILED.code())
        .operatorType(AuditLogConstants.OPERATOR_TYPE_SYSTEM)
        .operatorId(context.getWorkerId())
        .traceId(String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID)))
        .evidenceRef(null)
        .detailSummary(detailSummary)
        .build());
    return DispatchStageResult.success(stage());
  }
}
