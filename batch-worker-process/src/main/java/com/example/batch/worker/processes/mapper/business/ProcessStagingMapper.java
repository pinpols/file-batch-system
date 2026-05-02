package com.example.batch.worker.processes.mapper.business;

import java.time.Instant;
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
}
