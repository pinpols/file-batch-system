package io.github.pinpols.batch.orchestrator.infrastructure.admin;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.CleanupTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * 测试数据级联清理 repository。
 *
 * <p>Java 保留保护规则、依赖顺序和结果汇总，固定 SQL 由 {@link AdminTestDataCleanupMapper} 承载。事务边界由
 * AdminTestDataCleanupService 持有，console-api 只能通过内部代理触发。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AdminTestDataCleanupRepository {

  private static final CleanupTarget CLEAR_PARENT = CleanupTarget.CLEAR_JOB_INSTANCE_PARENT;
  private static final List<CleanupTarget> PREFIX_TARGETS = List.of(
      CleanupTarget.WORKFLOW_NODE_RUN,
      CleanupTarget.WORKFLOW_RUN,
      CleanupTarget.COMPENSATION_COMMAND,
      CleanupTarget.APPROVAL_COMMAND,
      CleanupTarget.PIPELINE_INSTANCE,
      CleanupTarget.JOB_EXECUTION_LOG,
      CleanupTarget.JOB_STEP_INSTANCE,
      CleanupTarget.JOB_TASK,
      CleanupTarget.JOB_PARTITION,
      CLEAR_PARENT,
      CleanupTarget.JOB_INSTANCE,
      CleanupTarget.WORKFLOW_NODE,
      CleanupTarget.WORKFLOW_EDGE,
      CleanupTarget.WORKFLOW_DEFINITION,
      CleanupTarget.JOB_DEFINITION,
      CleanupTarget.FILE_CHANNEL_CONFIG,
      CleanupTarget.FILE_TEMPLATE_CONFIG,
      CleanupTarget.API_KEY,
      CleanupTarget.ALERT_ROUTING_CONFIG,
      CleanupTarget.TENANT_QUOTA_POLICY,
      CleanupTarget.CONSOLE_USER_ACCOUNT,
      CleanupTarget.ARCHIVE_POLICY,
      CleanupTarget.TENANT);
  private static final List<CleanupTarget> EXACT_TENANT_TARGETS = List.of(
      CleanupTarget.WORKFLOW_NODE_RUN,
      CleanupTarget.WORKFLOW_RUN,
      CleanupTarget.PIPELINE_STEP_RUN,
      CleanupTarget.PIPELINE_INSTANCE,
      CleanupTarget.JOB_TASK,
      CleanupTarget.JOB_STEP_INSTANCE,
      CleanupTarget.JOB_PARTITION,
      CLEAR_PARENT,
      CleanupTarget.JOB_INSTANCE,
      CleanupTarget.APPROVAL_COMMAND,
      CleanupTarget.FILE_ERROR_RECORD,
      CleanupTarget.FILE_DISPATCH_RECORD,
      CleanupTarget.FILE_RECORD,
      CleanupTarget.WORKFLOW_EDGE,
      CleanupTarget.WORKFLOW_NODE,
      CleanupTarget.WORKFLOW_DEFINITION,
      CleanupTarget.PIPELINE_STEP_DEFINITION,
      CleanupTarget.PIPELINE_DEFINITION,
      CleanupTarget.JOB_DEFINITION,
      CleanupTarget.FILE_CHANNEL_CONFIG,
      CleanupTarget.FILE_TEMPLATE_CONFIG,
      CleanupTarget.ARCHIVE_POLICY,
      CleanupTarget.CONSOLE_USER_ACCOUNT,
      CleanupTarget.TENANT);
  private static final Set<String> PROTECTED_TENANT_IDS = CommonConstants.PROTECTED_TENANT_IDS;

  private final AdminTestDataCleanupMapper mapper;

  /** 按业务键前缀清理核心配置与运行实例表。prefix 已由 Controller 层白名单校验。 */
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    String escapedPrefix = escapeLike(prefix);
    String prefixLike = escapedPrefix + "-%";
    String operatorPrefixLike = "op-" + escapedPrefix + "-%";
    Map<String, Integer> result = new LinkedHashMap<>();
    for (CleanupTarget target : PREFIX_TARGETS) {
      int affected = mapper.cleanupByPrefix(target, prefixLike, operatorPrefixLike);
      addVisibleResult(result, target, affected);
    }
    logResult("prefix=" + prefix, result);
    return result;
  }

  /** 按精确 tenantId 列表清理，补充 prefix 模式无法覆盖的纯短名残留。 */
  public Map<String, Integer> cleanupByExactTenantIds(List<String> tenantIds) {
    if (EmptyChecks.isEmpty(tenantIds)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    for (String id : tenantIds) {
      if (PROTECTED_TENANT_IDS.contains(id.toLowerCase(Locale.ROOT))) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.tenant.protected_cannot_delete", id);
      }
    }

    Map<String, Integer> result = new LinkedHashMap<>();
    for (CleanupTarget target : EXACT_TENANT_TARGETS) {
      int affected = mapper.cleanupByTenantIds(target, tenantIds);
      addVisibleResult(result, target, affected);
    }
    logResult("tenantIds=" + tenantIds, result);
    return result;
  }

  private static void addVisibleResult(
      Map<String, Integer> result, CleanupTarget target, int affected) {
    if (target != CLEAR_PARENT) {
      result.put(target.name().toLowerCase(Locale.ROOT), affected);
    }
  }

  private static void logResult(String selector, Map<String, Integer> result) {
    int totalDeleted = result.values().stream().mapToInt(Integer::intValue).sum();
    log.info(
        "[admin] test-data cleanup {} totalDeleted={} breakdown={}",
        selector,
        totalDeleted,
        result);
  }

  private static String escapeLike(String input) {
    if (EmptyChecks.isEmpty(input)) {
      return input;
    }
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
