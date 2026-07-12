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
 *
 * <p>P0 观测(ADR-038 生产化):{@link #METRIC_OPERATIONS} 计数写入 / 命中 / 跳过等操作,配合 {@link
 * #METRIC_RESUME_SKIPPED} 计量命中续跑时被跳过(已在上次执行完成)的记录数 —— 前者回答“续跑有没有在起作用、命中率多少”,
 * 后者回答“续跑省下了多少重复工作”。两者标签仅 stage(枚举),不带 tenant / instance,保持低基数。
 */
@Component
public class DefaultProcessingPositionStore implements ProcessingPositionStore {

  static final String METRIC_OPERATIONS = "batch.worker.checkpoint.operations.total";

  /**
   * 命中续跑时被跳过的已处理记录数(counter,按 stage 累加 {@code processedCount})。用 {@code increase()}
   * 观测某时段续跑省下的重复处理量;计数为 0 的命中(位点存在但尚无已提交记录)也计入命中次数 ({@link #METRIC_OPERATIONS}
   * outcome=resumable),只是本 counter 不增。
   */
  static final String METRIC_RESUME_SKIPPED =
      "batch.worker.checkpoint.resume.skipped.records.total";

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
        // P1-2:completed 行保留 position_marker(Export 完成 marker 含文件字节数指纹,GenerateStep
        // 幂等跳过前须据此校验残文件完整性);LOAD completed 路径只用 processedCount,保留 marker 无副作用。
        return new ProcessingPosition(row.positionMarker(), row.processedCount(), true);
      }
      record(stage, "load", "resumable");
      recordResumeSkipped(stage, row.processedCount());
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

  @Override
  public void deleteAllStages(String tenantId, long pipelineInstanceId) {
    try {
      mapper.deleteByInstance(tenantId, pipelineInstanceId);
      // 实例级操作,无单一 stage 维度:用固定 "ALL" 标签保持低基数。
      recordAllStages("delete", "success");
    } catch (RuntimeException exception) {
      recordAllStages("delete", "failure");
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

  private void recordAllStages(String operation, String outcome) {
    if (meterRegistry != null) {
      meterRegistry
          .counter(METRIC_OPERATIONS, "stage", "ALL", "operation", operation, "outcome", outcome)
          .increment();
    }
  }

  private void recordResumeSkipped(ProcessingStage stage, long skippedRecords) {
    if (meterRegistry != null && skippedRecords > 0L) {
      meterRegistry.counter(METRIC_RESUME_SKIPPED, "stage", stage.code()).increment(skippedRecords);
    }
  }
}
