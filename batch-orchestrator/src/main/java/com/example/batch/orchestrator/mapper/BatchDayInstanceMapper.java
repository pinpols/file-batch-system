package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.batch_day_instance} MyBatis 映射（替代原 Spring Data JDBC {@code
 * BatchDayInstanceRepository}，对齐 CLAUDE.md §架构硬约束 — 运行态走 MyBatis）。
 *
 * <p>关键约束：
 *
 * <ul>
 *   <li>{@link #insert(BatchDayInstanceEntity)} 用 {@code ON CONFLICT (tenant_id, calendar_code,
 *       biz_date) DO NOTHING}：并发首次创建只一行落库；caller 拿不到 id 也无所谓，下一轮 {@link
 *       #selectByTenantCalendarBizDate} 自然读到。
 *   <li>{@link #updateWithCas(BatchDayInstanceEntity)} 走 {@code WHERE id=? AND version=?} CAS：返回值 0
 *       视为乐观锁冲突，调用方应抛 {@code OptimisticLockingFailureException} 保留 SDJ 时代行为。
 * </ul>
 */
public interface BatchDayInstanceMapper {

  /**
   * 首次插入。{@code ON CONFLICT (uk_batch_day_instance) DO NOTHING}：并发竞争时只一行成功，无需特殊处理 UV。
   *
   * @return 实际写入行数（0 表示并发已被另一节点抢先创建）
   */
  int insert(BatchDayInstanceEntity record);

  /**
   * CAS 更新：必须 id + version 都匹配才生效。{@code SET version = version + 1}。
   *
   * @return 影响行数；0 表示 version 冲突 → 调用方抛 {@code OptimisticLockingFailureException}
   */
  int updateWithCas(BatchDayInstanceEntity record);

  /** 按租户 + 日历 + 业务日（unique constraint 三元组）查询当前行。 */
  BatchDayInstanceEntity selectByTenantCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  /** 扫描指定状态集合的批次日实例（用于 cutoff / settle 调度器候选过滤）。 */
  List<BatchDayInstanceEntity> selectByDayStatusIn(
      @Param("dayStatuses") Collection<String> dayStatuses);
}
