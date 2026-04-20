package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface WorkerRegistryMapper {

  int touchHeartbeat(TouchHeartbeatParam param);

  int markDecommissioned(
      @Param("tenantId") String tenantId,
      @Param("workerCode") String workerCode,
      @Param("heartbeatAt") Instant heartbeatAt);

  /**
   * 把 {@code heartbeat_at &lt; cutoff} 且当前是 ONLINE / DRAINING 的 worker 批量降级为 OFFLINE。
   * 不动 DECOMMISSIONED（已由人工/运维终止的 worker 不应被心跳扫描复活）。
   *
   * @return 被更新的行数
   */
  int markStaleHeartbeatsOffline(@Param("cutoff") Instant cutoff);
}
