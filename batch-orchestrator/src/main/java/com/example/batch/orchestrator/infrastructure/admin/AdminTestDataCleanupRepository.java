package com.example.batch.orchestrator.infrastructure.admin;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 测试数据级联清理 SQL repository。
 *
 * <p>按 FK 反向 DELETE。事务边界由 AdminTestDataCleanupService 持有，本类只承载 SQL。该清理由 orchestrator 执行，
 * console-api 只能通过内部代理触发。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AdminTestDataCleanupRepository {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * 按 prefix 级联清理 11 张核心配置 + 运行实例业务表(不是"零残留"全清)。
   *
   * @param prefix 已由 Controller 层正则约束 (`^[a-zA-Z][a-zA-Z0-9-]{2,32}$`,禁 `_/%/\\`),本方法不重复校验
   * @return 每张表删了多少行的 ordered map(LinkedHashMap 保留依赖顺序)
   */
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    String escapedPrefix = escapeLike(prefix);
    String like = escapedPrefix + "-%";
    String opLike = "op-" + escapedPrefix + "-%";
    Map<String, Integer> result = new LinkedHashMap<>();

    String jobInstanceSubquery =
        "SELECT id FROM batch.job_instance WHERE job_code LIKE :p ESCAPE '\\'";

    result.put(
        "workflow_node_run",
        jdbc.update(
            "DELETE FROM batch.workflow_node_run WHERE workflow_run_id IN (SELECT id FROM"
                + " batch.workflow_run WHERE workflow_definition_id IN (SELECT id FROM"
                + " batch.workflow_definition WHERE workflow_code LIKE :p ESCAPE '\\') OR"
                + " related_job_instance_id IN ("
                + jobInstanceSubquery
                + "))",
            new MapSqlParameterSource("p", like)));
    result.put(
        "workflow_run",
        jdbc.update(
            "DELETE FROM batch.workflow_run WHERE workflow_definition_id IN (SELECT id FROM"
                + " batch.workflow_definition WHERE workflow_code LIKE :p ESCAPE '\\') OR"
                + " related_job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));

    result.put(
        "compensation_command",
        jdbc.update(
            "DELETE FROM batch.compensation_command WHERE related_job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));
    result.put(
        "pipeline_instance",
        jdbc.update(
            "DELETE FROM batch.pipeline_instance WHERE related_job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));
    result.put(
        "job_execution_log",
        jdbc.update(
            "DELETE FROM batch.job_execution_log WHERE job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));
    result.put(
        "job_step_instance",
        jdbc.update(
            "DELETE FROM batch.job_step_instance WHERE job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));
    result.put(
        "job_task",
        jdbc.update(
            "DELETE FROM batch.job_task WHERE job_instance_id IN (" + jobInstanceSubquery + ")",
            new MapSqlParameterSource("p", like)));

    result.put(
        "job_partition",
        jdbc.update(
            "DELETE FROM batch.job_partition WHERE job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));

    jdbc.update(
        "UPDATE batch.job_instance SET parent_instance_id = NULL"
            + " WHERE parent_instance_id IN ("
            + jobInstanceSubquery
            + ")",
        new MapSqlParameterSource("p", like));
    result.put(
        "job_instance",
        jdbc.update(
            "DELETE FROM batch.job_instance WHERE job_code LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));

    result.put(
        "workflow_node",
        jdbc.update(
            "DELETE FROM batch.workflow_node WHERE workflow_definition_id IN (SELECT id FROM"
                + " batch.workflow_definition WHERE workflow_code LIKE :p ESCAPE '\\')",
            new MapSqlParameterSource("p", like)));
    result.put(
        "workflow_edge",
        jdbc.update(
            "DELETE FROM batch.workflow_edge WHERE workflow_definition_id IN (SELECT id FROM"
                + " batch.workflow_definition WHERE workflow_code LIKE :p ESCAPE '\\')",
            new MapSqlParameterSource("p", like)));
    result.put(
        "workflow_definition",
        jdbc.update(
            "DELETE FROM batch.workflow_definition WHERE workflow_code LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));

    result.put(
        "job_definition",
        jdbc.update(
            "DELETE FROM batch.job_definition WHERE job_code LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));
    result.put(
        "file_channel_config",
        jdbc.update(
            "DELETE FROM batch.file_channel_config WHERE channel_code LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));
    result.put(
        "file_template_config",
        jdbc.update(
            "DELETE FROM batch.file_template_config WHERE template_code LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));
    result.put(
        "console_user_account",
        jdbc.update(
            "DELETE FROM batch.console_user_account WHERE username LIKE :p ESCAPE '\\' OR username"
                + " LIKE :op ESCAPE '\\'",
            new MapSqlParameterSource("p", like).addValue("op", opLike)));
    result.put(
        "archive_policy",
        jdbc.update(
            "DELETE FROM batch.archive_policy WHERE tenant_id LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));
    result.put(
        "tenant",
        jdbc.update(
            "DELETE FROM batch.tenant WHERE tenant_id LIKE :p ESCAPE '\\'",
            new MapSqlParameterSource("p", like)));

    int totalDeleted = result.values().stream().mapToInt(Integer::intValue).sum();
    log.info(
        "[admin] test-data cleanup prefix={} totalDeleted={} breakdown={}",
        prefix,
        totalDeleted,
        result);
    return result;
  }

  private static String escapeLike(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 永远不删的白名单 —— 跟 scripts/db/wipe-non-system-tenants.sql `:keep` 同步。改这里要同步改 SQL。 */
  private static final Set<String> PROTECTED_TENANT_IDS = CommonConstants.PROTECTED_TENANT_IDS;

  /** 按精确 tenantId 列表清理。补充处理 prefix 模式清不掉的纯短名残留(td/te/tx 这类)。 */
  public Map<String, Integer> cleanupByExactTenantIds(List<String> tenantIds) {
    if (tenantIds == null || tenantIds.isEmpty()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    for (String id : tenantIds) {
      if (PROTECTED_TENANT_IDS.contains(id.toLowerCase())) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.tenant.protected_cannot_delete", id);
      }
    }
    MapSqlParameterSource params = new MapSqlParameterSource("ids", tenantIds);
    Map<String, Integer> result = new LinkedHashMap<>();

    result.put(
        "workflow_node_run",
        jdbc.update(
            "DELETE FROM batch.workflow_node_run WHERE workflow_run_id IN "
                + "(SELECT id FROM batch.workflow_run WHERE tenant_id IN (:ids))",
            params));
    result.put(
        "workflow_run",
        jdbc.update("DELETE FROM batch.workflow_run WHERE tenant_id IN (:ids)", params));
    result.put(
        "pipeline_step_run",
        jdbc.update(
            "DELETE FROM batch.pipeline_step_run WHERE pipeline_instance_id IN "
                + "(SELECT id FROM batch.pipeline_instance WHERE tenant_id IN (:ids))",
            params));
    result.put(
        "pipeline_instance",
        jdbc.update("DELETE FROM batch.pipeline_instance WHERE tenant_id IN (:ids)", params));
    result.put(
        "job_task", jdbc.update("DELETE FROM batch.job_task WHERE tenant_id IN (:ids)", params));
    result.put(
        "job_step_instance",
        jdbc.update("DELETE FROM batch.job_step_instance WHERE tenant_id IN (:ids)", params));
    result.put(
        "job_partition",
        jdbc.update("DELETE FROM batch.job_partition WHERE tenant_id IN (:ids)", params));
    jdbc.update(
        "UPDATE batch.job_instance SET parent_instance_id = NULL WHERE parent_instance_id IN"
            + " (SELECT id FROM batch.job_instance WHERE tenant_id IN (:ids))",
        params);
    result.put(
        "job_instance",
        jdbc.update("DELETE FROM batch.job_instance WHERE tenant_id IN (:ids)", params));

    result.put(
        "file_error_record",
        jdbc.update("DELETE FROM batch.file_error_record WHERE tenant_id IN (:ids)", params));
    result.put(
        "file_dispatch_record",
        jdbc.update("DELETE FROM batch.file_dispatch_record WHERE tenant_id IN (:ids)", params));
    result.put(
        "file_record",
        jdbc.update("DELETE FROM batch.file_record WHERE tenant_id IN (:ids)", params));

    result.put(
        "workflow_edge",
        jdbc.update(
            "DELETE FROM batch.workflow_edge WHERE workflow_definition_id IN "
                + "(SELECT id FROM batch.workflow_definition WHERE tenant_id IN (:ids))",
            params));
    result.put(
        "workflow_node",
        jdbc.update(
            "DELETE FROM batch.workflow_node WHERE workflow_definition_id IN "
                + "(SELECT id FROM batch.workflow_definition WHERE tenant_id IN (:ids))",
            params));
    result.put(
        "workflow_definition",
        jdbc.update("DELETE FROM batch.workflow_definition WHERE tenant_id IN (:ids)", params));
    result.put(
        "pipeline_step_definition",
        jdbc.update(
            "DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id IN "
                + "(SELECT id FROM batch.pipeline_definition WHERE tenant_id IN (:ids))",
            params));
    result.put(
        "pipeline_definition",
        jdbc.update("DELETE FROM batch.pipeline_definition WHERE tenant_id IN (:ids)", params));
    result.put(
        "job_definition",
        jdbc.update("DELETE FROM batch.job_definition WHERE tenant_id IN (:ids)", params));

    result.put(
        "file_channel_config",
        jdbc.update("DELETE FROM batch.file_channel_config WHERE tenant_id IN (:ids)", params));
    result.put(
        "file_template_config",
        jdbc.update("DELETE FROM batch.file_template_config WHERE tenant_id IN (:ids)", params));
    result.put(
        "archive_policy",
        jdbc.update("DELETE FROM batch.archive_policy WHERE tenant_id IN (:ids)", params));
    result.put(
        "console_user_account",
        jdbc.update("DELETE FROM batch.console_user_account WHERE tenant_id IN (:ids)", params));
    result.put("tenant", jdbc.update("DELETE FROM batch.tenant WHERE tenant_id IN (:ids)", params));

    int totalDeleted = result.values().stream().mapToInt(Integer::intValue).sum();
    log.info(
        "[admin] test-data cleanup by exact ids={} totalDeleted={} breakdown={}",
        tenantIds,
        totalDeleted,
        result);
    return result;
  }
}
