package io.github.pinpols.batch.console.domain.ops.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Console 端 outbox_event 只读查询 mapper。
 *
 * <p>故意只保留 SELECT — CLAUDE.md「Orchestrator 是唯一状态主机」硬约束下，console 不能直接 UPDATE/DELETE
 * outbox_event；cleanup / republish 等运维操作由 ConsoleOrchestratorProxyService 转发到 orchestrator
 * 内部接口（/internal/outbox/*）执行。
 */
public interface ConsoleOutboxEventReadMapper {

  long countByStatus(
      @Param("tenantId") String tenantId, @Param("publishStatus") String publishStatus);

  List<Map<String, Object>> statsByStatus(@Param("tenantId") String tenantId);
}
