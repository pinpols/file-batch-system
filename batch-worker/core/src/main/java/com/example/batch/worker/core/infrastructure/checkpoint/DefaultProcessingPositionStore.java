package com.example.batch.worker.core.infrastructure.checkpoint;

import com.example.batch.worker.core.domain.PipelineProgressEntity;
import com.example.batch.worker.core.mapper.PipelineProgressMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ProcessingPositionStore} 默认实现 — 经 {@link PipelineProgressMapper} 落 {@code
 * batch.pipeline_progress}。
 *
 * <p>无事务注解:{@link #advance} 由调用方(LoadStep / GenerateStep)的 {@code @Transactional} 边界统一管,保证业务写 +
 * 位点推进同事务。{@link #load} / {@link #markCompleted} 走默认提交语义即可。
 */
@Component
@RequiredArgsConstructor
public class DefaultProcessingPositionStore implements ProcessingPositionStore {

  private final PipelineProgressMapper mapper;

  @Override
  public ProcessingPosition load(String tenantId, long pipelineInstanceId, ProcessingStage stage) {
    PipelineProgressEntity row =
        mapper.findByInstanceAndStage(tenantId, pipelineInstanceId, stage.code());
    if (row == null) {
      return ProcessingPosition.empty();
    }
    if (row.completed()) {
      return ProcessingPosition.completed(row.processedCount());
    }
    return new ProcessingPosition(row.positionMarker(), row.processedCount(), false);
  }

  @Override
  public void advance(
      String tenantId,
      long pipelineInstanceId,
      ProcessingStage stage,
      String newMarker,
      long processedCountIncrement) {
    mapper.advance(tenantId, pipelineInstanceId, stage.code(), newMarker, processedCountIncrement);
  }

  @Override
  public void markCompleted(String tenantId, long pipelineInstanceId, ProcessingStage stage) {
    mapper.markCompleted(tenantId, pipelineInstanceId, stage.code());
  }
}
