package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 分发完成阶段：汇总回执状态并写入审计日志，作为整个分发 pipeline 的终态步骤。 */
@Component
public class CompleteDispatchStep implements DispatchStageStep {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_RECEIPT_CODE = "receiptCode";

  private final PlatformFileRuntimeRepository runtimeRepository;

  public CompleteDispatchStep(PlatformFileRuntimeRepository runtimeRepository) {
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.COMPLETE;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不更新 file_record 状态 / 不写 audit。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return DispatchStageResult.success(stage());
    }
    Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
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
    Long fileId = runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    String receiptStatus = String.valueOf(attrs.getOrDefault("receiptStatus", "NONE"));
    if ("SUCCESS".equalsIgnoreCase(receiptStatus)) {
      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put("channelCode", dispatchPayload.channelCode());
      if (attrs.get(KEY_RECEIPT_CODE) != null) {
        fileMetadata.put(KEY_RECEIPT_CODE, attrs.get(KEY_RECEIPT_CODE));
      }
      runtimeRepository.updateFileStatus(fileId, "DISPATCHED", fileMetadata);
    }
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put("channelCode", dispatchPayload.channelCode());
    detailSummary.put("dispatchTarget", dispatchPayload.dispatchTarget());
    detailSummary.put("externalRequestId", attrs.get("externalRequestId"));
    detailSummary.put(KEY_RECEIPT_CODE, attrs.get(KEY_RECEIPT_CODE));
    detailSummary.put("receiptStatus", receiptStatus);
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("DISPATCH_COMPLETE")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(String.valueOf(attrs.getOrDefault("externalRequestId", "")))
            .detailSummary(detailSummary)
            .build());
    return DispatchStageResult.success(stage());
  }
}
