package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 管理员测试数据清理 Mapper。删除目标由枚举封闭，不接受动态表名。 */
public interface AdminTestDataCleanupMapper {

  enum CleanupTarget {
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
    JOB_INSTANCE,
    FILE_ERROR_RECORD,
    FILE_DISPATCH_RECORD,
    FILE_RECORD,
    WORKFLOW_NODE,
    WORKFLOW_EDGE,
    WORKFLOW_DEFINITION,
    PIPELINE_STEP_DEFINITION,
    PIPELINE_DEFINITION,
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

  default int cleanupByPrefix(CleanupTarget target, String prefixLike, String operatorPrefixLike) {
    return cleanupByPrefixTarget(target.name(), prefixLike, operatorPrefixLike);
  }

  int cleanupByPrefixTarget(
      @Param("target") String target,
      @Param("prefixLike") String prefixLike,
      @Param("operatorPrefixLike") String operatorPrefixLike);

  default int cleanupByTenantIds(CleanupTarget target, List<String> tenantIds) {
    return cleanupByTenantIdsTarget(target.name(), tenantIds);
  }

  int cleanupByTenantIdsTarget(
      @Param("target") String target, @Param("tenantIds") List<String> tenantIds);
}
