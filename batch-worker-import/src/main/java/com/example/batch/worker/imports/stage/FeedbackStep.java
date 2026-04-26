package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的 FEEDBACK 阶段（终止步骤）：汇总 parse/validate/load 各阶段的记录统计， 以 {@code IMPORT_FEEDBACK}
 * 操作类型写入审计日志，不做任何状态更新。
 *
 * <p>本步骤始终返回成功，是 Import pipeline 的最后一个阶段。
 */
@Component
public class FeedbackStep implements ImportStageStep {

  private final PlatformFileRuntimeRepository runtimeRepository;

  public FeedbackStep(PlatformFileRuntimeRepository runtimeRepository) {
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public ImportStage stage() {
    return ImportStage.FEEDBACK;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put("parsedCount", context.getAttributes().get("parsedCount"));
    detailSummary.put("validatedCount", context.getAttributes().get("validatedCount"));
    detailSummary.put("loadedCount", context.getAttributes().get("loadedCount"));
    detailSummary.put(
        "pipelineInstanceId",
        context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("IMPORT_FEEDBACK")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(null)
            .detailSummary(detailSummary)
            .build());
    return ImportStageResult.success(stage());
  }
}
