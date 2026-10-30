package io.github.pinpols.batch.worker.processes.mapper.business;

import io.github.pinpols.batch.worker.processes.cleanup.ProcessStagingGenerationCandidate;
import io.github.pinpols.batch.worker.processes.query.ProcessStagingExpiredScanQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.process_staging} 业务表 Mapper（业务库 = {@code processBusinessDataSource}）。
 *
 * <p>仅负责 PROCESS worker 的 staging 表运维操作（孤儿清理、最老行年龄观测）。本 Mapper 绑定 {@code
 * processBusinessSqlSessionFactory}，运行在业务库连接池上，不与 platform 库共用事务。
 */
public interface ProcessStagingMapper {

  /** 只读预览下一轮有界的批次日游标扫描。 */
  List<ProcessStagingGenerationCandidate> listExpiredGenerations(
      @Param("tenantId") String tenantId,
      @Param("retentionHours") int retentionHours,
      @Param("batchSize") int batchSize,
      @Param("offset") int offset);

  /** 按创建时间及代际身份续扫，已回收候选退出集合时不跳过后续代际。 */
  List<ProcessStagingGenerationCandidate> listExpiredGenerationsAfter(
      ProcessStagingExpiredScanQuery query);

  long countExpiredGenerations(
      @Param("tenantId") String tenantId, @Param("retentionHours") int retentionHours);

  /** 供 Console 调用的租户隔离内部端点；所有决策仍由 Orchestrator 负责。 */
  List<ProcessStagingGenerationCandidate> listActiveGenerationsAfter(
      @Param("tenantId") String tenantId,
      @Param("generationId") String generationId,
      @Param("batchSize") int batchSize);

  long countGenerationRowsOlderThan(
      @Param("candidate") ProcessStagingGenerationCandidate candidate,
      @Param("retentionHours") int retentionHours);

  Instant selectOldestGenerationRow(
      @Param("candidate") ProcessStagingGenerationCandidate candidate);

  /** 用于提前声明预期分组的上游文件清单内部幂等接收入口。 */
  String selectGenerationStatusForUpdate(ProcessStagingGenerationCandidate candidate);

  /** 封存目标由入口固定，候选身份不携带可覆盖的状态。 */
  default int sealOpenGeneration(ProcessStagingGenerationCandidate candidate) {
    final String sealedStatus = "SEALED";
    return sealOpenGenerationWithStatus(candidate, sealedStatus);
  }

  int sealOpenGenerationWithStatus(
      @Param("candidate") ProcessStagingGenerationCandidate candidate,
      @Param("targetStatus") String targetStatus);

  /** 原 SQL 继续原子核验已封存且无残行，才完成回收。 */
  default int markGenerationReclaimedIfEmpty(ProcessStagingGenerationCandidate candidate) {
    final String reclaimedStatus = "RECLAIMED";
    return markGenerationReclaimedIfEmptyWithStatus(candidate, reclaimedStatus);
  }

  int markGenerationReclaimedIfEmptyWithStatus(
      @Param("candidate") ProcessStagingGenerationCandidate candidate,
      @Param("targetStatus") String targetStatus);

  /** 已持久化作业依赖等待记录的租户隔离只读运维视图。 */
  String setGenerationRlsContext(@Param("generationId") String generationId);

  /** 某租户日历当前由运维人员控制的自动补扫暂停状态。 */
  int deleteGenerationRows(
      @Param("candidate") ProcessStagingGenerationCandidate candidate,
      @Param("batchSize") int batchSize);

  /**
   * 幂等创建一个天级 RANGE 子分区(已存在则 no-op)。
   *
   * <p>分区名 / 边界全部由 {@link
   * io.github.pinpols.batch.worker.processes.cleanup.ProcessStagingOrphanCleaner} 从日期派生(形如 {@code
   * process_staging_p20260607} / {@code 2026-06-07 00:00:00+00}),非用户输入,无注入面。父表索引由 PG 自动附加到新分区。
   *
   * @param partitionName 子分区表名(不含 schema 前缀),如 {@code process_staging_p20260607}
   * @param fromTs 含下界,UTC 时间戳字面量,如 {@code 2026-06-07 00:00:00+00}
   * @param toTs 不含上界,UTC 时间戳字面量,如 {@code 2026-06-08 00:00:00+00}
   */
  void createDailyPartition(
      @Param("partitionName") String partitionName,
      @Param("fromTs") String fromTs,
      @Param("toTs") String toTs);
}
