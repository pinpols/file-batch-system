package io.github.pinpols.batch.worker.core.infrastructure.checkpoint;

import io.github.pinpols.batch.worker.core.domain.PipelineProgressEntity;
import io.github.pinpols.batch.worker.core.mapper.PipelineProgressMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ProcessingPositionStore} 默认实现 — 经 {@link PipelineProgressMapper} 落 {@code
 * batch.pipeline_progress}。
 *
 * <p>无事务注解:{@link #advance} 参与平台库调用方事务。Import 业务数据可能位于租户业务库,无法与平台库位点组成单库事务, 其崩溃窗口依赖插件幂等约束兜底;详见
 * ADR-038 实施勘误。指标只使用 stage/operation/outcome 固定标签,避免实例或租户维度造成高基数。
 */
@Component
public class DefaultProcessingPositionStore implements ProcessingPositionStore {

  static final String METRIC_OPERATIONS = "batch.worker.checkpoint.operations.total";

  private final PipelineProgressMapper mapper;
  private final MeterRegistry meterRegistry;

  public DefaultProcessingPositionStore(
      PipelineProgressMapper mapper, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.mapper = mapper;
    this.meterRegistry = meterRegistryProvider.getIfAvailable();
  }

  @Override
  public ProcessingPosition load(String tenantId, long pipelineInstanceId, ProcessingStage stage) {
    try {
      PipelineProgressEntity row =
          mapper.findByInstanceAndStage(tenantId, pipelineInstanceId, stage.code());
      if (row == null) {
        record(stage, "load", "empty");
        return ProcessingPosition.empty();
      }
      if (row.completed()) {
        record(stage, "load", "completed");
        return ProcessingPosition.completed(row.processedCount());
      }
      record(stage, "load", "resumable");
      return new ProcessingPosition(row.positionMarker(), row.processedCount(), false);
    } catch (RuntimeException exception) {
      record(stage, "load", "failure");
      throw exception;
    }
  }

  @Override
  public void advance(
      String tenantId,
      long pipelineInstanceId,
      ProcessingStage stage,
      String newMarker,
      long processedCountIncrement) {
    try {
      mapper.advance(
          tenantId, pipelineInstanceId, stage.code(), newMarker, processedCountIncrement);
      record(stage, "advance", "success");
    } catch (RuntimeException exception) {
      record(stage, "advance", "failure");
      throw exception;
    }
  }

  @Override
  public void markCompleted(String tenantId, long pipelineInstanceId, ProcessingStage stage) {
    try {
      mapper.markCompleted(tenantId, pipelineInstanceId, stage.code());
      record(stage, "complete", "success");
    } catch (RuntimeException exception) {
      record(stage, "complete", "failure");
      throw exception;
    }
  }

  private void record(ProcessingStage stage, String operation, String outcome) {
    if (meterRegistry != null) {
      meterRegistry
          .counter(
              METRIC_OPERATIONS, "stage", stage.code(), "operation", operation, "outcome", outcome)
          .increment();
    }
  }
}
