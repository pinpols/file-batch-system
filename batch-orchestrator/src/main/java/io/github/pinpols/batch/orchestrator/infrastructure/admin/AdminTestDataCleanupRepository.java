package io.github.pinpols.batch.orchestrator.infrastructure.admin;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.CleanupTarget;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.OutboxEventIdentity;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.PrefixCleanupScope;
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
      CleanupTarget.EVENT_DELIVERY_LOG,
      CleanupTarget.EVENT_OUTBOX_RETRY,
      CleanupTarget.OUTBOX_EVENT,
      CleanupTarget.OUTBOX_EVENT_DEDUP_KEY,
      CleanupTarget.SCHEDULED_OCCURRENCE_CATCH_UP_AUDIT,
      CleanupTarget.SCHEDULED_OCCURRENCE,
      CleanupTarget.SCHEDULED_OCCURRENCE_COVERAGE,
      CleanupTarget.EXPECTED_WORK_OCCURRENCE,
      CleanupTarget.WORKFLOW_LAUNCH_WAIT,
      CleanupTarget.JOB_DEPENDENCY_WAITING_LAUNCH,
      CleanupTarget.BATCH_DAY_WAITING_LAUNCH,
      CleanupTarget.TRIGGER_MISFIRE_PENDING,
      CleanupTarget.WORKFLOW_NODE_RUN,
      CleanupTarget.WORKFLOW_RUN,
      CleanupTarget.COMPENSATION_COMMAND,
      CleanupTarget.APPROVAL_COMMAND,
      CleanupTarget.PIPELINE_STEP_RUN,
      CleanupTarget.PIPELINE_INSTANCE,
      CleanupTarget.JOB_EXECUTION_LOG,
      CleanupTarget.JOB_STEP_INSTANCE,
      CleanupTarget.JOB_TASK,
      CleanupTarget.JOB_PARTITION,
      CLEAR_PARENT,
      CleanupTarget.JOB_MONITORING_ALERT_CLAIM,
      CleanupTarget.JOB_INSTANCE,
      CleanupTarget.TRIGGER_OUTBOX_EVENT,
      CleanupTarget.TRIGGER_REQUEST,
      CleanupTarget.FILE_GROUP_EXPECTATION_DEFINITION,
      CleanupTarget.EXTERNAL_EVENT_EXPECTATION_DEFINITION,
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
      CleanupTarget.EVENT_DELIVERY_LOG,
      CleanupTarget.EVENT_OUTBOX_RETRY,
      CleanupTarget.OUTBOX_EVENT,
      CleanupTarget.OUTBOX_EVENT_DEDUP_KEY,
      CleanupTarget.SCHEDULED_OCCURRENCE_CATCH_UP_AUDIT,
      CleanupTarget.SCHEDULED_OCCURRENCE,
      CleanupTarget.SCHEDULED_OCCURRENCE_COVERAGE,
      CleanupTarget.EXPECTED_WORK_OCCURRENCE,
      CleanupTarget.WORKFLOW_LAUNCH_WAIT,
      CleanupTarget.JOB_DEPENDENCY_WAITING_LAUNCH,
      CleanupTarget.BATCH_DAY_WAITING_LAUNCH,
      CleanupTarget.TRIGGER_MISFIRE_PENDING,
      CleanupTarget.WORKFLOW_NODE_RUN,
      CleanupTarget.WORKFLOW_RUN,
      CleanupTarget.COMPENSATION_COMMAND,
      CleanupTarget.APPROVAL_COMMAND,
      CleanupTarget.PIPELINE_STEP_RUN,
      CleanupTarget.PIPELINE_INSTANCE,
      CleanupTarget.JOB_EXECUTION_LOG,
      CleanupTarget.JOB_STEP_INSTANCE,
      CleanupTarget.JOB_TASK,
      CleanupTarget.JOB_PARTITION,
      CLEAR_PARENT,
      CleanupTarget.JOB_MONITORING_ALERT_CLAIM,
      CleanupTarget.JOB_INSTANCE,
      CleanupTarget.FILE_ERROR_RECORD,
      CleanupTarget.FILE_DISPATCH_RECORD,
      CleanupTarget.FILE_RECORD,
      CleanupTarget.TRIGGER_OUTBOX_EVENT,
      CleanupTarget.TRIGGER_REQUEST,
      CleanupTarget.FILE_GROUP_EXPECTATION_DEFINITION,
      CleanupTarget.EXTERNAL_EVENT_EXPECTATION_DEFINITION,
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
  private static final Set<String> PROTECTED_TENANT_IDS = CommonConstants.PROTECTED_TENANT_IDS;

  private final AdminTestDataCleanupMapper mapper;

  /** 按业务键前缀清理核心配置与运行实例表。prefix 已由 Controller 层白名单校验。 */
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    String escapedPrefix = escapeLike(prefix);
    String prefixLike = escapedPrefix + "-%";
    String operatorPrefixLike = "op-" + escapedPrefix + "-%";
    PrefixCleanupScope scope = capturePrefixCleanupScope(prefixLike, operatorPrefixLike);
    if (mapper.hasAmbiguousJobInstanceIds(scope)) {
      // 弱引用仅保存数字 ID；跨日或跨租户重号时不能猜测其归属，也不能开始任何删除。
      log.warn("[admin] prefix cleanup refused: ambiguous JobInstance ids, prefix={}", prefix);
      throw BizException.of(ResultCode.CONFLICT, "error.common.conflict");
    }
    Map<String, Integer> result = new LinkedHashMap<>();
    for (CleanupTarget target : PREFIX_TARGETS) {
      int affected = mapper.cleanupByPrefix(target, scope);
      addVisibleResult(result, target, affected);
    }
    logResult("prefix=" + prefix, result);
    return result;
  }

  private PrefixCleanupScope capturePrefixCleanupScope(String prefixLike, String operatorPrefixLike) {
    PrefixCleanupScope discovery =
        new PrefixCleanupScope(prefixLike, operatorPrefixLike, List.of(), List.of(), List.of());
    List<Long> runIds = mapper.findWorkflowRunIdsByPrefix(discovery);
    PrefixCleanupScope runScope =
        new PrefixCleanupScope(prefixLike, operatorPrefixLike, runIds, List.of(), List.of());
    List<Long> requestIds = mapper.findTriggerRequestIdsByPrefix(runScope);
    PrefixCleanupScope requestScope =
        new PrefixCleanupScope(prefixLike, operatorPrefixLike, runIds, requestIds, List.of());
    List<OutboxEventIdentity> events = mapper.findOutboxEventsByPrefix(requestScope);
    return new PrefixCleanupScope(prefixLike, operatorPrefixLike, runIds, requestIds, events);
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
    String escapedBackslashes = input.replace("\\", "\\\\");
    String escapedPercentSigns = escapedBackslashes.replace("%", "\\%");
    return escapedPercentSigns.replace("_", "\\_");
  }
}
