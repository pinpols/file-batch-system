package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
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

  // ── 替代原 WorkerRegistryRepository 的查询方法（运行态走 MyBatis 闭环 3/3） ─────────────

  /** 按 (tenant_id, worker_code) unique constraint 单行查询。 */
  WorkerRegistryRecord selectByTenantAndWorkerCode(
      @Param("tenantId") String tenantId, @Param("workerCode") String workerCode);

  /** 按租户 + 状态过滤；用于 selector / snapshot service / drain timeout 等。 */
  List<WorkerRegistryRecord> selectByTenantAndStatus(
      @Param("tenantId") String tenantId, @Param("status") String status);

  /** 按租户 + worker_group + 状态过滤；用于 selector 在 group 维度选 worker。 */
  List<WorkerRegistryRecord> selectByTenantAndWorkerGroupAndStatus(
      @Param("tenantId") String tenantId,
      @Param("workerGroup") String workerGroup,
      @Param("status") String status);

  /** 跨租户按状态扫描（如 drain timeout 调度器扫所有 DRAINING worker）。 */
  List<WorkerRegistryRecord> selectByStatus(@Param("status") String status);

  long countByTenantAndStatus(@Param("tenantId") String tenantId, @Param("status") String status);

  long countByTenantAndWorkerGroupAndStatus(
      @Param("tenantId") String tenantId,
      @Param("workerGroup") String workerGroup,
      @Param("status") String status);

  /**
   * Worker 首次注册：插入新行。{@code ON CONFLICT (tenant_id, worker_code) DO NOTHING}：并发同一 workerCode 多次
   * register 只第一行成功，调用方下一次 selectByTenantAndWorkerCode 自然读到已有行。
   *
   * @return 实际写入行数（0 表示并发已被另一节点抢先创建）
   */
  int insert(WorkerRegistryRecord record);

  /**
   * register / status / drain 等更新路径的统一 upsert：按 id 全字段覆盖 status / heartbeat_at / current_load /
   * capability_tags / drain_started_at / drain_deadline_at；不参与 CAS（worker_registry 不是状态机推进核心， 业务上由
   * mapper.touchHeartbeat / markDecommissioned 等单字段 SQL 接管热路径，本方法仅供 register / forceOffline
   * 等冷路径全字段写入）。
   *
   * @return 影响行数（0 表示行不存在）
   */
  int updateById(WorkerRegistryRecord record);

  /**
   * 测试夹具用 SDJ-like 等价 save 助手：record.id() 为空时走 {@link #insert} + 重新 select 拿到带 id 的版本， 否则走 {@link
   * #updateById}。仅供 integration / test fixture 使用，不要在生产路径调用（生产 callsite 应明确知道走的是新建还是更新，调对应方法语义更清晰）。
   */
  default WorkerRegistryRecord saveLikeSdj(WorkerRegistryRecord record) {
    if (record.id() == null) {
      insert(record);
      return selectByTenantAndWorkerCode(record.tenantId(), record.workerCode());
    }
    updateById(record);
    return selectByTenantAndWorkerCode(record.tenantId(), record.workerCode());
  }
}
