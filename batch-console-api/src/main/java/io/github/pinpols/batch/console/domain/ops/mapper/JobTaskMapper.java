package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.entity.JobTaskHeartbeatEntity;
import org.apache.ibatis.annotations.Param;

/** console-api 只读访问 {@code batch.job_task} 的心跳进度子集(SDK Phase 4 / ORCH-P4-1)。 */
public interface JobTaskMapper {

  /** 按租户 + taskId 读心跳进度;租户维度强制,跨租户读不到(返回 null)。 */
  JobTaskHeartbeatEntity selectHeartbeatByTenantAndId(
      @Param("tenantId") String tenantId, @Param("taskId") Long taskId);
}
