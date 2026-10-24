package io.github.pinpols.batch.orchestrator.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 管理员测试数据清理 Mapper。删除目标由枚举封闭，不接受动态表名。 */
public interface AdminTestDataCleanupMapper {

  enum CleanupTarget {
    EVENT_DELIVERY_LOG,
    EVENT_OUTBOX_RETRY,
    OUTBOX_EVENT,
    OUTBOX_EVENT_DEDUP_KEY,
    SCHEDULED_OCCURRENCE_CATCH_UP_AUDIT,
    SCHEDULED_OCCURRENCE,
    SCHEDULED_OCCURRENCE_COVERAGE,
    EXPECTED_WORK_OCCURRENCE,
    WORKFLOW_LAUNCH_WAIT,
    JOB_DEPENDENCY_WAITING_LAUNCH,
    BATCH_DAY_WAITING_LAUNCH,
    TRIGGER_MISFIRE_PENDING,
    TRIGGER_OUTBOX_EVENT,
    TRIGGER_REQUEST,
    FILE_GROUP_EXPECTATION_DEFINITION,
    EXTERNAL_EVENT_EXPECTATION_DEFINITION,
    WORKFLOW_NODE_RUN,
    WORKFLOW_RUN,
    COMPENSATION_COMMAND,
    APPROVAL_COMMAND,
    PIPELINE_STEP_RUN,
    PIPELINE_INSTANCE,
    JOB_EXECUTION_LOG,
    JOB_STEP_INSTANCE,
    JOB_TASK,
    JOB_PARTITION,
    CLEAR_JOB_INSTANCE_PARENT,
    JOB_MONITORING_ALERT_CLAIM,
    JOB_INSTANCE,
    FILE_ERROR_RECORD,
    FILE_DISPATCH_RECORD,
    FILE_RECORD,
    WORKFLOW_NODE,
    WORKFLOW_EDGE,
    WORKFLOW_DEFINITION,
    JOB_DEFINITION,
    FILE_CHANNEL_CONFIG,
    FILE_TEMPLATE_CONFIG,
    API_KEY,
    ALERT_ROUTING_CONFIG,
    TENANT_QUOTA_POLICY,
    CONSOLE_USER_ACCOUNT,
    ARCHIVE_POLICY,
    TENANT
  }

  /** 删除前冻结来源身份，父记录删除后仍能准确定位请求与 Outbox 子记录。 */
  record PrefixCleanupScope(
      String prefixLike,
      String operatorPrefixLike,
      List<Long> workflowRunIds,
      List<Long> triggerRequestIds,
      List<OutboxEventIdentity> outboxEvents) {
    public PrefixCleanupScope {
      workflowRunIds = List.copyOf(workflowRunIds);
      triggerRequestIds = List.copyOf(triggerRequestIds);
      outboxEvents = List.copyOf(outboxEvents);
    }
  }

  /** 普通 Outbox 使用完整分区身份；幂等台账在事件删除后仍按原事件键清理。 */
  record OutboxEventIdentity(String tenantId, Long id, OffsetDateTime createdAt, String eventKey) {}

  List<Long> findWorkflowRunIdsByPrefix(@Param("scope") PrefixCleanupScope scope);

  List<Long> findTriggerRequestIdsByPrefix(@Param("scope") PrefixCleanupScope scope);

  List<OutboxEventIdentity> findOutboxEventsByPrefix(@Param("scope") PrefixCleanupScope scope);

  boolean hasAmbiguousJobInstanceIds(@Param("scope") PrefixCleanupScope scope);

  default int cleanupByPrefix(CleanupTarget target, PrefixCleanupScope scope) {
    return cleanupByPrefixTarget(target.name(), scope);
  }

  int cleanupByPrefixTarget(@Param("target") String target, @Param("scope") PrefixCleanupScope scope);

  default int cleanupByTenantIds(CleanupTarget target, List<String> tenantIds) {
    return cleanupByTenantIdsTarget(target.name(), tenantIds);
  }

  int cleanupByTenantIdsTarget(
      @Param("target") String target, @Param("tenantIds") List<String> tenantIds);
}
