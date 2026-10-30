package io.github.pinpols.batch.worker.core.mapper;

import io.github.pinpols.batch.worker.core.domain.PipelineProgressEntity;
import io.github.pinpols.batch.worker.core.param.PipelineProgressAdvanceParam;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.pipeline_progress} 数据访问 — ADR-038 续跑位点。
 *
 * <p>设计点:{@link #advance} 是 UPSERT,首次为 INSERT,之后为 UPDATE，参与调用方平台库事务。Import 业务写位于租户业务库时，按
 * ADR-038 使用业务先提交、位点后推进及插件幂等，不能假设两个数据源自动组成单库原子事务。
 */
public interface PipelineProgressMapper {

  /** 启动续跑用:按 (tenantId, pipelineInstanceId, stage) 取唯一行;无则返回 null。 */
  PipelineProgressEntity findByInstanceAndStage(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") long pipelineInstanceId,
      @Param("stage") String stage);

  /**
   * UPSERT 位点；返回0表示父实例不存在或不属于调用租户，调用方必须拒绝成功。{@code processedCountIncrement} 累加到当前 processed_count。已
   * completed 的行不会被回写位点(防止迟到 chunk 把已完成位点撤回)。
   */
  int advance(PipelineProgressAdvanceParam parameters);

  /** 阶段整体完成时调用;completed=true + completed_at = now。幂等(已 completed 不变)。 */
  int markCompleted(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") long pipelineInstanceId,
      @Param("stage") String stage);

  /**
   * 删除某 pipeline 实例的**全部 stage 位点**(ADR-038 P0 补偿协同)。
   *
   * <p>安全增量补偿(compensate_on_failure)反向删除了本 run 的业务行后,advance 已推进的位点会指向被删数据; 若不作废,重试复用同一 {@code
   * pipelineInstanceId} 续跑会跳过已删 chunk → 数据永久缺失。故补偿成功后必须清位点, 让重试从头全量重做。带 {@code tenant_id}
   * 谓词纵深防御(表有该列);{@code pipelineInstanceId} 本身全局唯一。
   *
   * @return 删除行数
   */
  int deleteByInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") long pipelineInstanceId);
}
