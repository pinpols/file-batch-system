package io.github.pinpols.batch.orchestrator.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.List;
import java.util.Set;

/**
 * 治理护栏:batch-orchestrator mapper XML 中 {@code <if test="tenantId != null">AND tenant_id =
 * #{tenantId}</if>} 这种"可空"租户过滤,只允许在已知的 ROLE_ADMIN 跨租运维入口存在。新增 mapper 必须走无条件 {@code AND tenant_id =
 * #{tenantId}} 或加入白名单并写明原因。
 *
 * <p>规则源自 batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest},此处仅声明本模块白名单。
 */
class MapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /**
   * 已知 ROLE_ADMIN 跨租运维查询 / dashboard 聚合的 mapper 白名单。新写 mapper **严禁追加** — 应改成无条件租户过滤(典型业务路径)或拆出独立的
   * admin 全局方法。
   */
  @Override
  protected List<String> knownConditionalTenantMappers() {
    return List.of(
        // 跨租 retry / outbox 重试观察台 — admin 全局查
        "EventDeliveryLogMapper",
        "EventOutboxRetryMapper",
        "RetryScheduleMapper",
        "OutboxEventMapper",
        // 跨租 batch-day 等待视图 — admin 全局排查
        "BatchDayWaitingLaunchMapper",
        // 跨租文件治理聚合
        "FileGovernanceMapper");
  }

  /**
   * batch.* UPDATE/DELETE 缺 tenant_id 谓词的语句级豁免(表带 tenant_id 列,但隔离不在本条 SQL 的 WHERE)。每条注明 by-design
   * 依据。红线:新写 业务/用户可达的 batch.* 写严禁往此追加,必须带 tenant_id 谓词。
   */
  @Override
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of(
        // 认证路径:按刚认证通过的 key 自身 id 更新(只能触及自己那把 key,非 IDOR)
        "ApiKeyAuthMapper#touchLastUsedAt",
        "ApiKeyAuthMapper#upgradeHashIfLegacy",
        // orchestrator 内部乐观锁状态机:按全局 id + version CAS,非用户可达
        "BatchDayInstanceMapper#updateWithCas",
        "BatchDayReplayEntryMapper#updateStatus",
        "QuotaRuntimeStateMapper#updateWithCas",
        // 跨租后台 reaper:按状态/时间全表扫僵尸行,加 tenant 谓词会破坏(by-design 跨租)
        "CompensationCommandMapper#markStaleRunningFailed",
        "OutboxEventMapper#resetStalePublishing",
        "WorkerRegistryMapper#markStaleHeartbeatsOffline",
        // 发件箱发布器状态机:发布器全局轮询、按全局行 id 推进,无 tenant 上下文(by-design 系统组件)
        "OutboxEventMapper#deleteByIds",
        "OutboxEventMapper#deleteEventDeliveryLogsByOutboxIds",
        "OutboxEventMapper#deleteEventOutboxRetriesByOutboxIds",
        // 重试调度器:全局认领后按 id 推进状态(claimForRetry 已由调度器选定行)
        "RetryScheduleMapper#markRunning",
        "RetryScheduleMapper#markSuccess",
        "RetryScheduleMapper#markFailed",
        "RetryScheduleMapper#resetToWaiting",
        // worker 自更新:worker 按自己的注册 id 改
        "WorkerRegistryMapper#updateById",
        // 归档/保留级联:按预选 job_instance id 集删除运行态子行(内部维护任务,id 集即隔离边界)
        "SuccessInstanceArchiveMapper#nullifyParentInstanceIdByParentIds",
        "SuccessInstanceArchiveMapper#deleteJobInstancesByIds",
        "SuccessInstanceArchiveMapper#nullifyPipelineInstanceFileIdByInstanceIds",
        "SuccessInstanceArchiveMapper#deletePipelineInstancesByInstanceIds",
        "SuccessInstanceArchiveMapper#deleteJobPartitionsByInstanceIds",
        "SuccessInstanceArchiveMapper#deleteJobStepInstancesByInstanceIds",
        "SuccessInstanceArchiveMapper#deleteJobTasksByInstanceIds",
        "SuccessInstanceArchiveMapper#deleteJobExecutionLogsByInstanceIds",
        "SuccessInstanceArchiveMapper#deleteCompensationCommandsByInstanceIds",
        "SuccessInstanceArchiveMapper#deleteWorkflowRunsByInstanceIds",
        // file_dispatch_record 有 tenant_id 列,但归档按预选实例 id 集级联删派单明细(id 集即隔离边界)
        "SuccessInstanceArchiveMapper#deleteFileDispatchRecordsByInstanceIds",
        // 定义子表级联删除:按父 workflow_definition_id / ids 删(父定义已按 tenant 校验)
        "WorkflowEdgeMapper#deleteByWorkflowDefinitionId",
        "WorkflowNodeMapper#deleteByWorkflowDefinitionId",
        "WorkflowRunMapper#deleteByIds");
  }
}
