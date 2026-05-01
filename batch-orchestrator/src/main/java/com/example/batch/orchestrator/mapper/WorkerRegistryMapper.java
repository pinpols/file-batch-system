package com.example.batch.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkerRegistryMapper {

  int touchHeartbeat(TouchHeartbeatParam param);

  int markDecommissioned(
      @Param("tenantId") String tenantId, @Param("workerCode") String workerCode);

  /**
   * 把 {@code heartbeat_at &lt; current_timestamp - timeoutSeconds} 且当前是 ONLINE / DRAINING 的 worker
   * 批量降级为 OFFLINE。不动 DECOMMISSIONED（已由人工/运维终止的 worker 不应被心跳扫描复活）。
   *
   * <p>cutoff 由 DB 直接计算，消除 orchestrator JVM / worker JVM / DB 三方时钟漂移：worker 心跳 SQL 写入 heartbeat_at
   * = current_timestamp（DB 时钟），扫描比对也用 current_timestamp，时间基准完全统一。
   *
   * @param timeoutSeconds 心跳容忍秒数（含 grace period 的累加值）
   * @return 被更新的行数
   */
  int markStaleHeartbeatsOffline(@Param("timeoutSeconds") long timeoutSeconds);

  /**
   * 扫描 ONLINE / DRAINING 的 worker，返回 {@code capability_tags} 不符合"字符串数组"约定的行。
   *
   * <p>约定：{@code capability_tags} 要么为 NULL / 空串（表示无能力标签），要么是 JSONB 字符串数组（如 {@code
   * ["ingest","delivery"]}）。不符合的形态（对象、标量、含非字符串元素的数组）会让 {@link
   * com.example.batch.orchestrator.infrastructure.scheduler.DefaultWorkerSelector} 静默把该 worker
   * 视为"无能力"跳过，建议由审计调度器定期暴露。
   */
  List<InvalidCapabilityTagsRecord> selectInvalidCapabilityTags();
}
