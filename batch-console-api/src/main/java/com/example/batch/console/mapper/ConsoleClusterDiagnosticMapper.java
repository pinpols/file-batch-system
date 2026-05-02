package com.example.batch.console.mapper;

import com.example.batch.console.domain.view.cluster.DeliveryStatusCountView;
import com.example.batch.console.domain.view.cluster.ShedLockView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * console 集群诊断查询 (MyBatis,迁移自 ConsoleClusterDiagnosticRepository)。
 *
 * <ul>
 *   <li>{@link #shedlockAll()} — 平台级 batch.shedlock 全表扫描(无 tenant_id)
 *   <li>{@link #eventDeliveryStatusCounts(String)} — 按 delivery_status 分组统计
 *   <li>{@link #countPendingOutboxEvents(String)} — NEW 状态的 outbox 待发计数
 * </ul>
 *
 * <p>纯只读;调用方在 {@code ConsoleClusterDiagnosticService} 用 {@code @Transactional(readOnly = true)} 包装,
 * 自动走 {@code ReadReplicaRoutingDataSource} 读副本。
 */
public interface ConsoleClusterDiagnosticMapper {

  List<ShedLockView> shedlockAll();

  List<DeliveryStatusCountView> eventDeliveryStatusCounts(@Param("tenantId") String tenantId);

  Long countPendingOutboxEvents(@Param("tenantId") String tenantId);
}
