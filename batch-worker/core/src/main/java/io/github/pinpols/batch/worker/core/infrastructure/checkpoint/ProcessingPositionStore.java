package io.github.pinpols.batch.worker.core.infrastructure.checkpoint;

/**
 * ADR-038 续跑位点抽象 — Import LOAD / Export GENERATE 阶段共用。
 *
 * <p>实现要点:
 *
 * <ul>
 *   <li>{@link #advance} <b>不开新事务</b>:参与调用方的平台库事务。Import 业务写若落租户业务库,无法与平台库位点组成
 *       单库事务,需依靠插件幂等能力覆盖“业务已提交、位点未推进”的崩溃窗口。
 *   <li>{@link #load} / {@link #markCompleted} 自带读 / 写事务即可,不强约束。
 *   <li>无位点行视为"首次跑",返回 {@link ProcessingPosition#empty()}。
 *   <li>已 {@code completed=true} 行不会被回写位点(防止迟到 chunk 把已完成位点撤回),交由 DB UPSERT 守护。
 * </ul>
 */
public interface ProcessingPositionStore {

  /** 启动续跑:读 (tenantId, pipelineInstanceId, stage) 当前位点;无则 {@link ProcessingPosition#empty()}。 */
  ProcessingPosition load(String tenantId, long pipelineInstanceId, ProcessingStage stage);

  /**
   * UPSERT 位点。本方法不开新事务,参与调用方的平台库事务;不承诺跨数据源原子性。
   *
   * @param newMarker 本 chunk/page 末尾的位置标记
   * @param processedCountIncrement 本批新增的已处理记录数
   */
  void advance(
      String tenantId,
      long pipelineInstanceId,
      ProcessingStage stage,
      String newMarker,
      long processedCountIncrement);

  /** 阶段整体完成时调用;completed=true 后续 advance 不再回写位点。 */
  void markCompleted(String tenantId, long pipelineInstanceId, ProcessingStage stage);

  /**
   * 作废某 pipeline 实例的**全部 stage 位点**(ADR-038 P0 补偿协同)。
   *
   * <p>安全增量补偿(compensate_on_failure)反向删除本 run 的业务行后调用:advance 已推进的位点指向被删数据, 必须一并作废,否则重试复用同一 {@code
   * pipelineInstanceId} 续跑会跳过已删 chunk 造成数据永久缺失。
   */
  void deleteAllStages(String tenantId, long pipelineInstanceId);
}
