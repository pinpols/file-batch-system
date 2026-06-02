package com.example.batch.worker.core.mapper;

import com.example.batch.worker.core.domain.PipelineProgressEntity;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.pipeline_progress} 数据访问 — ADR-038 续跑位点。
 *
 * <p>设计点:{@link #advance} 是 UPSERT,首次为 INSERT,之后为 UPDATE;与 chunk/page 业务写**必须在同一事务**,由调用方 (LoadStep
 * / GenerateStep)保证。
 */
public interface PipelineProgressMapper {

  /** 启动续跑用:按 (tenantId, pipelineInstanceId, stage) 取唯一行;无则返回 null。 */
  PipelineProgressEntity findByInstanceAndStage(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") long pipelineInstanceId,
      @Param("stage") String stage);

  /**
   * UPSERT 位点;与本批业务写同事务调用。{@code processedCountIncrement} 累加到当前 processed_count(NULL 视 0)。 已
   * completed 的行不会被回写位点(防止迟到 chunk 把已完成位点撤回)。
   */
  int advance(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") long pipelineInstanceId,
      @Param("stage") String stage,
      @Param("positionMarker") String positionMarker,
      @Param("processedCountIncrement") long processedCountIncrement);

  /** 阶段整体完成时调用;completed=true + completed_at = now。幂等(已 completed 不变)。 */
  int markCompleted(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") long pipelineInstanceId,
      @Param("stage") String stage);
}
