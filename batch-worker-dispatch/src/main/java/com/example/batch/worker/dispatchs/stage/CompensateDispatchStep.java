package com.example.batch.worker.dispatchs.stage;

import com.example.batch.common.service.DryRunGuard;
import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 分发补偿阶段：在投递彻底失败后将分发记录标记为 COMPENSATED，并写入审计日志。 */
@Component
public class CompensateDispatchStep implements DispatchStageStep {

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  private final FileDispatchRepository fileDispatchRepository;
  private final PlatformFileRuntimeRepository runtimeRepository;

  public CompensateDispatchStep(
      FileDispatchRepository fileDispatchRepository,
      PlatformFileRuntimeRepository runtimeRepository) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.COMPENSATE;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不发外部补偿（也没真投递过），跳过即可。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return DispatchStageResult.success(stage());
    }
    Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_COMPENSATE_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    int updated =
        fileDispatchRepository.markCompensated(
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
    runtimeRepository.updateFileStatus(
        fileId, "FAILED", Map.of("channelCode", dispatchPayload.channelCode()));
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put("channelCode", dispatchPayload.channelCode());
    detailSummary.put("dispatchTarget", dispatchPayload.dispatchTarget());
    detailSummary.put("externalRequestId", context.getAttributes().get("externalRequestId"));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("DISPATCH_COMPENSATE")
            .operationResult("FAILED")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(null)
            .detailSummary(detailSummary)
            .build());
    return DispatchStageResult.success(stage());
  }
}
