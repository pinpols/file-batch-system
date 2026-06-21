package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceMetrics;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import com.example.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import com.example.batch.orchestrator.domain.query.JobInstanceQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface JobInstanceMapper {

  JobInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  JobInstanceEntity selectByInstanceNo(
      @Param("tenantId") String tenantId, @Param("instanceNo") String instanceNo);

  JobInstanceEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  /** 同一 dedup_key 下已用到的最大 run_attempt，未出现过则返回 null。供 RERUN 路径原子推进。 */
  Integer selectMaxRunAttemptByDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  /**
   * 指定租户 + jobCode + bizDate 下**最新一次 attempt**(run_attempt 最大、同值取 id 最大)的实例状态。
   *
   * <p>供 ADR-043 依赖感知 fire 就绪查询:只有最新 attempt 为 {@code SUCCESS} 才算就绪。
   *
   * <p>相较"存在任意 SUCCESS",此口径能正确处理「先成功、后 rerun 失败 / rerun 正在跑」—— 此时最新 attempt 非
   * SUCCESS,下游不应按过期结果放行(防结算级误放行)。无任何实例时返回 null。
   */
  String selectLatestStatusByBizDate(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("bizDate") LocalDate bizDate);

  int insert(JobInstanceEntity entity);

  List<JobInstanceEntity> selectByQuery(JobInstanceQuery query);

  int updateProgress(UpdateInstanceProgressParam param);

  int markRunning(MarkInstanceRunningParam param);

  int updateExpectedPartitionCount(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedPartitionCount") Integer expectedPartitionCount,
      @Param("expectedVersion") Long expectedVersion);

  List<JobInstanceEntity> selectSlaViolationCandidates(@Param("limit") int limit);

  long countSlaViolationCandidates();

  /**
   * 选 sla_alerted_at 早于 {@code escalationBefore} 且 instance_status 仍非终态（WAITING/READY/RUNNING）的实例。
   * 用于升级再触发：首次告警后跑了一段时间还没结束就转为 ERROR 级再发一次。
   */
  List<JobInstanceEntity> selectSlaEscalationCandidates(
      @Param("escalationBefore") Instant escalationBefore, @Param("limit") int limit);

  /** 升级候选总数（gauge 用）。 */
  long countSlaEscalationCandidates(@Param("escalationBefore") Instant escalationBefore);

  /**
   * 选 RUNNING 中超过 {@code job_definition.timeout_seconds} 的实例（业务级 timeout 回退）。
   *
   * <p>JOIN job_definition 拿 timeout_seconds（{@code > 0} 才生效，{@code = 0} 表示无 timeout）。
   *
   * <p>与 {@code selectSlaViolationCandidates} 区别：SLA 看 {@code deadline_at /
   * expected_duration_seconds}（业务 SLA 软告警，不变更状态）；timeout 看 {@code job_definition.timeout_seconds}（硬
   * fail 终态）。
   */
  List<JobInstanceEntity> selectTimedOutCandidates(@Param("limit") int limit);

  /**
   * 选 launch T1 已提交但 T2 从未完成的非 workflow 实例。仅包含 CREATED、零 partition、零 task、trigger_request 仍
   * ACCEPTED 的实例,供保守恢复调度器重驱 T2。
   */
  List<JobInstanceEntity> selectStaleCreatedLaunchCandidates(
      @Param("olderThan") Instant olderThan, @Param("limit") int limit);

  int markSlaAlerted(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("slaAlertedAt") Instant slaAlertedAt);

  long countActiveByTenant(@Param("tenantId") String tenantId);

  long countActiveByTenantAndQueueCode(
      @Param("tenantId") String tenantId, @Param("queueCode") String queueCode);

  long countActiveByFairShareGroup(@Param("fairShareGroup") String fairShareGroup);

  /**
   * R7-A3-P1：批量预聚合多个 fair_share_group → count，替代 N+1 单条查询。
   *
   * @return {@code Map<{fairShareGroup,cnt}>} 列表；未出现的 group = 0。
   */
  List<Map<String, Object>> countActiveByFairShareGroups(
      @Param("fairShareGroups") Collection<String> fairShareGroups);

  /**
   * R7-A3-P1：批量预聚合多个 queue_code → count，替代 N+1 单条查询。
   *
   * @return {@code Map<{queueCode,cnt}>} 列表；未出现的 queue = 0。
   */
  List<Map<String, Object>> countActiveByTenantAndQueueCodes(
      @Param("tenantId") String tenantId, @Param("queueCodes") Collection<String> queueCodes);

  /** 统计所有租户的运行中任务总量（WAITING/READY/RUNNING）。 */
  long countActiveAll();

  long countTerminalInstancesWithActiveChildren();

  BatchDayInstanceMetrics selectBatchDayMetrics(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<JobInstanceEntity> selectBatchDayCatchUpCandidates(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  /**
   * ADR-020 batch_day_replay 候选筛选：按 (tenant, calendarCode, bizDate) 内每个 job_code 取最新一行实例， 由
   * statuses 与 jobCodes 双重过滤。
   *
   * <ul>
   *   <li>{@code statuses} 非空 → 实例状态在集合内（如 ALL: SUCCESS/FAILED/PARTIAL_FAILED；ALL_FAILED:
   *       FAILED/PARTIAL_FAILED）；空集合等价于不限；
   *   <li>{@code jobCodes} 非空 → 仅这些 jobCode 的实例（SUBSET_JOB_CODES 模式）；空集合等价于不限。
   * </ul>
   */
  List<JobInstanceEntity> selectBatchDayCandidates(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("statuses") List<String> statuses,
      @Param("jobCodes") List<String> jobCodes);

  int updateStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("instanceStatus") String instanceStatus,
      @Param("finishedAt") Instant finishedAt,
      @Param("expectedVersion") Long expectedVersion);

  /**
   * 非终态生命周期状态 CAS 更新(ADR-044 pause/resume)。
   *
   * <p>仅当 version 匹配且当前非终态时推进,不动 finished_at。返回 0 表示版本冲突或已终态。
   */
  int updateLifecycleStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("instanceStatus") String instanceStatus,
      @Param("expectedVersion") Long expectedVersion);

  /**
   * 取同一 (tenantId, jobDefinitionId) 下最近一次完整成功(仅 SUCCESS)实例。 用于增量模式启动新实例时把上一次的 {@code
   * high_water_mark_out} 当作本次 IN。
   *
   * <p>不取 PARTIAL_FAILED:部分 partition 未覆盖时若把它的水位推给下次,会让下次跳过失败 partition 应处理的数据范围;由 publish 的 {@code
   * ON CONFLICT} 兜住重复执行幂等。没有历史成功实例(首次跑)返回 null, worker 在业务侧按"从头跑"处理。
   */
  JobInstanceEntity selectLastSuccessByJobDefinition(
      @Param("tenantId") String tenantId, @Param("jobDefinitionId") Long jobDefinitionId);

  /**
   * worker report 成功时把 OUT 水位回写。numeric CAS 守护:仅当新值更大时才推进,防止多 partition 并发回报顺序乱时慢 partition 用低水位
   * 覆写快 partition 已写的高水位。返回 0 行表示水位未推进(已被更高值占据 / 新值格式非法),调用方按 debug 记录不抛错。
   */
  int updateHighWaterMarkOut(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("highWaterMarkOut") String highWaterMarkOut);

  /**
   * 同一 (tenantId, jobCode, bizDate) 下,是否存在尚未到达终态的 job_instance。 BatchDayGateService 的 SAME_JOB
   * scope 用此 判断"前一日同 job 是否已完结"。
   *
   * <p>非终态: CREATED / WAITING / READY / RUNNING / PARTIAL_FAILED。 终态(允许放行): SUCCESS / FAILED /
   * CANCELLED / TERMINATED — 失败状态由调用方决定是否仍允许放行(与 batch_day_instance.day_status 同语义)。
   */
  int countNonTerminalByJobCodeAndBizDate(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("bizDate") LocalDate bizDate);

  /**
   * 同一 (tenantId, jobGroupCode, bizDate) 下,是否存在尚未到达终态的 job_instance。 BatchDayGateService 的
   * SAME_JOB_GROUP scope 用此判断"前一日同组是否全部完结"。 通过 JOIN job_definition.job_group_code 收敛同组 jobCode。
   */
  int countNonTerminalByJobGroupAndBizDate(
      @Param("tenantId") String tenantId,
      @Param("jobGroupCode") String jobGroupCode,
      @Param("bizDate") LocalDate bizDate);

  /**
   * ADR-022 v0.1 forensic 取证：按 (tenantId, bizDate 范围, 可选 jobCodes) 拉取所有 instance。 仅 forensic
   * 路径用，不在主链路。
   */
  List<JobInstanceEntity> selectForensicByBizDateRange(
      @Param("tenantId") String tenantId,
      @Param("bizDateFrom") LocalDate bizDateFrom,
      @Param("bizDateTo") LocalDate bizDateTo,
      @Param("jobCodes") List<String> jobCodes,
      @Param("limit") int limit);
}
