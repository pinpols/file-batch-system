package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.quota_runtime_state} MyBatis 映射（替代原 Spring Data JDBC {@code
 * QuotaRuntimeStateRepository}，对齐 CLAUDE.md §架构硬约束 — 运行态走 MyBatis）。
 *
 * <p>关键约束：
 *
 * <ul>
 *   <li>{@link #insert(QuotaRuntimeStateEntity)} 用 {@code ON CONFLICT (tenant_id, quota_scope,
 *       owner_code) DO NOTHING}：并发首次创建只允许一行写入数据库，第二个并发请求 insert affected=0， 不抛 UV，调用方下一轮
 *       loadOrCreate 会通过 {@link #selectByTenantQuotaScopeOwner} 拿到已有行。
 *   <li>{@link #updateWithCas(QuotaRuntimeStateEntity)} 走 {@code WHERE id=? AND version=?} CAS：返回值
 *       0 视为乐观锁冲突，调用方应抛 {@code OptimisticLockingFailureException} 保留 SDJ 时代行为。
 *   <li>id 不回填到 record（record 不可变；调用方下次 loadOrCreate 自然读到 id）。
 * </ul>
 */
public interface QuotaRuntimeStateMapper {

  /**
   * 首次插入。{@code ON CONFLICT DO NOTHING}：并发竞争时只一行成功写入数据库，调用方无需特殊处理 UV。
   *
   * @return 实际写入行数（0 表示并发已被另一节点抢先创建）
   */
  int insert(QuotaRuntimeStateEntity record);

  /**
   * CAS 更新：必须 id + version 都匹配才生效。{@code SET version = version + 1}。
   *
   * @return 影响行数；0 表示 version 冲突 → 调用方抛 {@code OptimisticLockingFailureException}
   */
  int updateWithCas(QuotaRuntimeStateEntity record);

  /** 按租户 + 维度 + owner（unique constraint 三元组）查询当前行。 */
  QuotaRuntimeStateEntity selectByTenantQuotaScopeOwner(
      @Param("tenantId") String tenantId,
      @Param("quotaScope") String quotaScope,
      @Param("ownerCode") String ownerCode);

  /** 扫描已过期窗口（{@code window_expires_at <= now} 且 policy 是 CALENDAR_DAY/SLIDING_WINDOW）。 */
  List<QuotaRuntimeStateEntity> selectExpired(@Param("now") Instant now);
}
