package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
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
    // ADR-026: 演练模式不写 audit 日志（避免污染实盘 file_audit 历史），直接返回 success。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return ImportStageResult.success(stage());
    }
    Map<String, Object> attrs = context.getAttributes();
    Long fileId = runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    Map<String, Object> detailSummary = new LinkedHashMap<>();
    detailSummary.put(
        PipelineRuntimeKeys.IMPORT_PARSED_COUNT,
        attrs.get(PipelineRuntimeKeys.IMPORT_PARSED_COUNT));
    detailSummary.put(
        PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT,
        attrs.get(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT));
    detailSummary.put(
        PipelineRuntimeKeys.IMPORT_LOADED_COUNT,
        attrs.get(PipelineRuntimeKeys.IMPORT_LOADED_COUNT));
    detailSummary.put("pipelineInstanceId", attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("IMPORT_FEEDBACK")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(null)
            .detailSummary(detailSummary)
            .build());
    return ImportStageResult.success(stage());
  }
}
