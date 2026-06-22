package com.example.batch.worker.core.infrastructure.checkpoint;

/**
 * ADR-038 续跑位点抽象 — Import LOAD / Export GENERATE 阶段共用。
 *
 * <p>实现要点:
 *
 * <ul>
 *   <li>{@link #advance} <b>不开新事务</b>:必须在调用方(LoadStep / GenerateStep)的 {@code @Transactional}
 *       边界内执行,与 chunk/page 业务写合一事务。
 *   <li>{@link #load} / {@link #markCompleted} 自带读 / 写事务即可,不强约束。
 *   <li>无位点行视为"首次跑",返回 {@link ProcessingPosition#empty()}。
 *   <li>已 {@code completed=true} 行不会被回写位点(防止迟到 chunk 把已完成位点撤回),交由 DB UPSERT 守护。
 * </ul>
 */
public interface ProcessingPositionStore {

  /** 启动续跑:读 (tenantId, pipelineInstanceId, stage) 当前位点;无则 {@link ProcessingPosition#empty()}。 */
  ProcessingPosition load(String tenantId, long pipelineInstanceId, ProcessingStage stage);

  /**
   * UPSERT 位点。<b>同事务调用</b>:本方法不开新事务,事务边界由调用方 {@code @Transactional} 统一管,保证业务写 + 位点推进原子化。
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
}
