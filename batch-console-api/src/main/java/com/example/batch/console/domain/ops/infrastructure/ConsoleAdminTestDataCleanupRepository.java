package com.example.batch.console.domain.ops.infrastructure;

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
 * <p>按 FK 反向 DELETE。事务边界由 ConsoleAdminTestDataCleanupService 持有，本类只承载 SQL。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ConsoleAdminTestDataCleanupRepository {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * 按 prefix 级联清理 11 张**核心配置 + 运行实例**业务表(不是"零残留"全清):
   *
   * <ul>
   *   <li>**已覆盖**(按 FK 反向): workflow_node_run / workflow_run / workflow_node / workflow_edge /
   *       workflow_definition / job_partition / job_task / job_step_instance / job_execution_log /
   *       compensation_command / pipeline_instance / job_instance / job_definition /
   *       file_channel_config / file_template_config / console_user_account / archive_policy /
   *       tenant
   *   <li>**未覆盖(已知残留)**: trigger_request / trigger_outbox_event / outbox_event / event_delivery_log
   *       / event_outbox_retry / webhook_subscription / webhook_delivery_log / notification_channel
   *       / alert_routing_config / dead_letter_task / file_record / file_dispatch_record / 各种
   *       *_audit / *_history 表
   * </ul>
   *
   * <p>CI/E2E 清场场景下,未覆盖表会随每次跑积累 prefix=e2e- 的脏数据。需要彻底"零残留"建议 直接 DROP + recreate schema
   * 或扩展本方法白名单(每次扩展须配 IT 验证 FK 反向顺序)。
   *
   * @param prefix 已由 Controller 层正则约束 (`^[a-zA-Z][a-zA-Z0-9-]{2,32}$`,禁 `_/%/\\`),本方法不重复校验
   * @return 每张表删了多少行的 ordered map(LinkedHashMap 保留依赖顺序)
   */
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    // Controller 正则 PREFIX_PATTERN 已禁 `_/%/\` 这些 SQL LIKE 元字符,
    // 此处再做一次 service 层兜底转义(深度防御:Controller 规则未来若放开字符集,
    // service 仍能阻止"prefix=e2e_A 误匹配 e2eXA-%"这类管理员误删风险)。
    String escapedPrefix = escapeLike(prefix);
    String like = escapedPrefix + "-%";
    String opLike = "op-" + escapedPrefix + "-%"; // RBAC 测试创建的 op-${prefix}-xxx 用户
    Map<String, Integer> result = new LinkedHashMap<>();

    // 按 FK 反向清理。每段独立 SQL 便于 audit + 排障。
    // 依赖链(无 CASCADE,必须自上而下):
    //   workflow_node_run → workflow_run → (workflow_definition / job_instance)
    //   job_task / job_step_instance / job_execution_log / compensation_command / pipeline_instance
    // → job_instance
    //   job_partition (CASCADE) / job_instance.parent_instance_id (自引,先 NULL 再删)
    String jobInstanceSubquery =
        "SELECT id FROM batch.job_instance WHERE job_code LIKE :p ESCAPE '\\'";

    // 1) workflow 运行态
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

    // 2) job_instance 的非 CASCADE 依赖
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

    // 3) job_partition (CASCADE,显式 DELETE 保留 audit row count)
    result.put(
        "job_partition",
        jdbc.update(
            "DELETE FROM batch.job_partition WHERE job_instance_id IN ("
                + jobInstanceSubquery
                + ")",
            new MapSqlParameterSource("p", like)));

    // 4) job_instance:先 NULL parent_instance_id 解自引,再 DELETE
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

    // 5) workflow 定义态
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

    // 6) job_definition:trigger_runtime_state 是 CASCADE,无需显式
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

  /**
   * 转义 LIKE 元字符,配合 SQL 端 `ESCAPE '\'` 子句。
   *
   * <p>转义顺序:`\` 必须先转义(避免后续 `_/%` 被转义后又被吞)。
   */
  private static String escapeLike(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 永远不删的白名单 —— 跟 scripts/db/wipe-non-system-tenants.sql `:keep` 同步。改这里要同步改 SQL。 */
  private static final Set<String> PROTECTED_TENANT_IDS =
      Set.of("system", "default", "default-tenant", "ta", "tb", "tc");

  /**
   * 按精确 tenantId 列表清理。补刀 prefix 模式清不掉的纯短名残留(td/te/tx 这类)。
   *
   * <p>白名单(system/default/default-tenant/ta/tb/tc)出现在列表里直接抛拒绝整批,不静默跳过。
   *
   * <p>FK 顺序参考 wipe-non-system-tenants.sql:pipeline 运行 → workflow 运行 → job 实例链 → file 相关 → 各种 log →
   * workflow 定义 → pipeline 定义 → job 定义 → 配置 → 租户本体。
   */
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

    // 运行态 → 文件前置(pipeline 运行 + workflow 运行 + job 实例链)
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
    // job_instance 自引,先 NULL 再删
    jdbc.update(
        "UPDATE batch.job_instance SET parent_instance_id = NULL WHERE parent_instance_id IN"
            + " (SELECT id FROM batch.job_instance WHERE tenant_id IN (:ids))",
        params);
    result.put(
        "job_instance",
        jdbc.update("DELETE FROM batch.job_instance WHERE tenant_id IN (:ids)", params));

    // 文件相关(必须在 pipeline_instance / job_instance 之后)
    result.put(
        "file_error_record",
        jdbc.update("DELETE FROM batch.file_error_record WHERE tenant_id IN (:ids)", params));
    result.put(
        "file_dispatch_record",
        jdbc.update("DELETE FROM batch.file_dispatch_record WHERE tenant_id IN (:ids)", params));
    result.put(
        "file_record",
        jdbc.update("DELETE FROM batch.file_record WHERE tenant_id IN (:ids)", params));

    // workflow / pipeline / job 定义
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

    // 配置 + 用户 + 租户本体
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
