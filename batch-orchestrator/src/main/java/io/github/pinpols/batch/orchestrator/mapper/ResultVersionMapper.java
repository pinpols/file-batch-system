package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.result_version} MyBatis 映射（ADR-017）。
 *
 * <p>关键约束：
 *
 * <ul>
 *   <li>{@link #insert} 失败说明 {@code (tenant_id, business_key, version_no)} 唯一索引或 {@code
 *       uk_result_version_effective} partial unique 冲突 —— 调用方通常先 {@link #supersedePriorEffective} 再
 *       insert，避免 partial 冲突。
 *   <li>{@link #selectByJobInstanceId} 用于幂等保护：同一 job_instance 只能落 1 行 result_version。
 * </ul>
 */
public interface ResultVersionMapper {

  /** 新增一行 result_version。返回写入行数。 */
  int insert(ResultVersionEntity record);

  /**
   * 对同一 (tenant_id, business_key) 的版本写入加事务级锁。
   *
   * <p>该锁只在当前数据库事务内有效,用于串行化 selectMaxVersionNo / supersede / insert 组合,避免并发 AUTO_LATEST 同时写
   * EFFECTIVE 时撞 partial unique index。
   */
  int lockBusinessKey(@Param("tenantId") String tenantId, @Param("businessKey") String businessKey);

  /** 按 job_instance_id 反查；同一实例最多 1 行（写入幂等保护）。 */
  ResultVersionEntity selectByJobInstanceId(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /** 拿同 (tenant_id, business_key) 当前最大 version_no；空集返回 null。 */
  Integer selectMaxVersionNo(
      @Param("tenantId") String tenantId, @Param("businessKey") String businessKey);

  /**
   * 把当前 EFFECTIVE 行 CAS 推进到 SUPERSEDED。返回受影响行数（0 = 没有现存 EFFECTIVE，新版本可直接 EFFECTIVE）。
   *
   * <p>partial unique index 保证至多 1 行 EFFECTIVE，所以本 update 至多影响 1 行。
   */
  int supersedePriorEffective(
      @Param("tenantId") String tenantId,
      @Param("businessKey") String businessKey,
      @Param("deactivatedAt") Instant deactivatedAt);

  /** 查 (tenant_id, business_key) 的当前 EFFECTIVE 行；找不到返回 null。 */
  ResultVersionEntity selectEffective(
      @Param("tenantId") String tenantId, @Param("businessKey") String businessKey);

  /** 列出某 business_key 的所有版本（按 version_no 倒序）；用于 console 列表 + ops 排查。 */
  List<ResultVersionEntity> listVersionsByBusinessKey(
      @Param("tenantId") String tenantId,
      @Param("businessKey") String businessKey,
      @Param("limit") int limit);

  /** 按 (tenantId, id) 反查；console / promote 入口用。 */
  ResultVersionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  /**
   * R7-A3-P1：批量按 (tenantId, ids) 反查；替代 N+1 单条循环（BatchDayReplay materialize 等）。
   *
   * <p>调用方必须确保 ids 非空，mybatis foreach 空列表会产生 {@code IN ()} 语法错误。
   */
  List<ResultVersionEntity> selectByIds(
      @Param("tenantId") String tenantId, @Param("ids") Collection<Long> ids);

  /** PENDING → EFFECTIVE：CAS 到 EFFECTIVE，写 effective_at。 */
  int promoteToEffective(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("effectiveAt") Instant effectiveAt);

  /** PENDING → ARCHIVED 拒绝路径；不影响其它版本。 */
  int rejectPending(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("deactivatedAt") Instant deactivatedAt);

  /** SUPERSEDED → ARCHIVED 由 retention scheduler 推进；可选清空 payload_json。 */
  int archiveSuperseded(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("now") Instant now,
      @Param("clearPayload") boolean clearPayload);

  /** 找出所有 status='SUPERSEDED' 且 deactivated_at 早于 cutoff 的版本，scheduler 用。 */
  List<ResultVersionEntity> selectSupersededOlderThan(
      @Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
