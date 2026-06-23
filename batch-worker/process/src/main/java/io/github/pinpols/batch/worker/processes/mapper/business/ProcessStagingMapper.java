package io.github.pinpols.batch.worker.processes.mapper.business;

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

  /**
   * 删除 staged_at 早于 {@code now() - retentionHours} 的孤儿行，单次最多删 {@code batchSize} 条。
   *
   * <p>用 {@code WHERE ctid IN (子查询 LIMIT)} 限制锁范围，避免大表全表锁定。
   *
   * @return 实际删除行数
   */
  int deleteOrphansOlderThan(
      @Param("retentionHours") int retentionHours, @Param("batchSize") int batchSize);

  /**
   * 取当前 staging 中最老一行的 staged_at；表空时返 null（让调用方将"年龄"折算为 0 或 -1）。
   *
   * <p>供 Micrometer gauge 观测使用。
   */
  Instant selectMinStagedAt();

  long countOrphansOlderThan(@Param("retentionHours") int retentionHours);

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

  /**
   * 列出早于 {@code cutoffYmd}(YYYYMMDD)的天级子分区名,不含 DEFAULT 回退分区。
   *
   * @param cutoffYmd 截止日,形如 {@code 20260604};严格小于此日的日分区被返回
   */
  List<String> listExpiredDailyPartitions(@Param("cutoffYmd") String cutoffYmd);

  /** DROP 一个子分区(瞬间还空间给 OS)。partitionName 由调度器从分区名清单取得,非用户输入。 */
  void dropPartition(@Param("partitionName") String partitionName);
}
