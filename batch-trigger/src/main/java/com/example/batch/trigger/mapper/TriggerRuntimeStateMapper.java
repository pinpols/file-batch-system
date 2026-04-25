package com.example.batch.trigger.mapper;

import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * trigger_runtime_state 表的 MyBatis mapper。
 *
 * <p>语义与时间轮调度流程一一对应:
 *
 * <ul>
 *   <li>{@link #findReadyToSchedule} — slidingWindow 扫库,捞 next_fire_time 即将到达且未占位的行
 *   <li>{@link #claimForSchedule} — leader 把行占位推进 wheel(原子,撞 version 失败)
 *   <li>{@link #advanceAfterFire} — fire 成功/失败/跳过后推进 next_fire_time + 释放 marker
 *   <li>{@link #releaseStaleMarkers} — 周期清理崩溃 leader 的 stale 占位
 *   <li>{@link #insertOnReconcile} / {@link #deleteByJobDefinitionId} — TriggerReconciler 同步用
 * </ul>
 */
public interface TriggerRuntimeStateMapper {

  /**
   * 滑动窗口扫库:next_fire_time 在 horizon 之前,且 marker 未占位。
   *
   * <p>使用 FOR UPDATE SKIP LOCKED 避免 leader 漂移时撞锁;调用方在事务内立即调
   * {@link #claimForSchedule} 占位。
   */
  List<TriggerRuntimeStateEntity> findReadyToSchedule(
      @Param("horizon") Instant horizon, @Param("limit") int limit);

  /**
   * 占位:CAS 写 scheduled_fire_marker,撞 version 失败(其他 leader 已占)。
   *
   * @return 影响行数(0=别人占了,1=占位成功)
   */
  int claimForSchedule(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("marker") String marker);

  /**
   * fire 完成后推进 next_fire_time、写 last_fire_status、释放 marker。
   *
   * <p>无 version CAS:fire 完成后这一行的"调度权"已被本 leader 持有,顺序写安全。
   * 但如果 fire 期间 marker 被 release_stale 清掉,本次 advance 应该悄悄继续(下次扫库会重捡)。
   */
  int advanceAfterFire(
      @Param("id") Long id,
      @Param("nextFireTime") Instant nextFireTime,
      @Param("lastFireTime") Instant lastFireTime,
      @Param("lastFireStatus") String lastFireStatus,
      @Param("misfireDelta") long misfireDelta);

  /**
   * 周期(每 2 min)清理超 5 min 未释放的 marker,避免 leader 崩溃后 trigger 永久卡住。
   *
   * @return 释放的行数
   */
  int releaseStaleMarkers(@Param("staleBefore") Instant staleBefore);

  /**
   * TriggerReconciler 同步:DB 有 job_definition + 无 runtime_state 时 INSERT。
   *
   * <p>UNIQUE (job_definition_id) 保证幂等;并发 reconciler 撞键时由调用方捕获 DuplicateKey。
   */
  int insertOnReconcile(TriggerRuntimeStateEntity entity);

  /** TriggerReconciler 同步:job_definition.enabled=false → CASCADE 删 runtime_state。 */
  int deleteByJobDefinitionId(@Param("jobDefinitionId") Long jobDefinitionId);

  /** 单查(reconciler 检查 / 测试用)。 */
  TriggerRuntimeStateEntity selectByJobDefinitionId(
      @Param("jobDefinitionId") Long jobDefinitionId);

  /** 给灰度切换数据迁移 + 运维查询用:按 tenant 列出全部 trigger 状态。 */
  List<TriggerRuntimeStateEntity> selectByTenantId(@Param("tenantId") String tenantId);

  /** schedule_expr 修改时:重新计算的 next_fire_time 直接写回,清掉 marker。 */
  int rescheduleNextFireTime(
      @Param("id") Long id, @Param("nextFireTime") Instant nextFireTime);
}
