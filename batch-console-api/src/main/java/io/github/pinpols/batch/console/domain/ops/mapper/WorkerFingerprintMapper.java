package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.entity.WorkerFingerprintRow;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerFingerprintSummaryRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * console-api 只读访问 {@code batch.worker_registry} 指纹列(SDK Phase 5 / SDK-P5-3,console Lane D)。
 *
 * <p>背景:V163 给 worker_registry 加了 {@code build_id} / {@code sdk_version} 两列,SDK Phase 5 register
 * 时已上报。本 mapper 暴露租户视图,供运维灰度切 buildId 时排查与可视化(取代 SQL 直查)。
 *
 * <p>只读、走读写分离只读路径。worker_registry 由 orchestrator 写入,console-api 不写。
 */
public interface WorkerFingerprintMapper {

  /**
   * 列本租户「活跃 + 退场中」worker 指纹列表(heartbeat_at 倒序,上限 200 行)。
   *
   * <p>status IN ('ONLINE','DRAINING') — V3 worker_registry 表实际状态枚举(无 'ACTIVE')。
   */
  List<WorkerFingerprintRow> selectFingerprintsByTenant(@Param("tenantId") String tenantId);

  /** 按 (build_id, sdk_version) 聚合本租户 ONLINE worker 数,count desc。空值聚合为 "(unknown)"。 */
  List<WorkerFingerprintSummaryRow> selectFingerprintSummaryByTenant(
      @Param("tenantId") String tenantId);
}
