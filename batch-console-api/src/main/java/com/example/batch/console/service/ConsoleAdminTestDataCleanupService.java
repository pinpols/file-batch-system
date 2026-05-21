package com.example.batch.console.service;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试数据级联清理 service(从 ConsoleAdminTestDataController 抽出)。
 *
 * <p>动机:CLAUDE.md §Java 编码细则 #4 @Transactional 只放 Service,不放 Controller。原 Controller
 * 持 @Transactional + 30+ jdbc.update 调用违反约定,本类承接事务 + SQL 执行,Controller 退化为薄壳。
 *
 * <p>按 FK 反向 DELETE,跑在 {@code @Transactional} 里要么全成要么全回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleAdminTestDataCleanupService {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * 按 prefix 级联清理 11 张业务表 + 子表。
   *
   * @param prefix 已由 Controller 层正则约束,本方法不重复校验
   * @return 每张表删了多少行的 ordered map(LinkedHashMap 保留依赖顺序)
   */
  @Transactional
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    String like = prefix + "-%";
    String opLike = "op-" + prefix + "-%"; // RBAC 测试创建的 op-${prefix}-xxx 用户
    Map<String, Integer> result = new LinkedHashMap<>();

    // 按 FK 反向清理。每段独立 SQL 便于 audit + 排障。
    // 依赖链(无 CASCADE,必须自上而下):
    //   workflow_node_run → workflow_run → (workflow_definition / job_instance)
    //   job_task / job_step_instance / job_execution_log / compensation_command / pipeline_instance
    // → job_instance
    //   job_partition (CASCADE) / job_instance.parent_instance_id (自引,先 NULL 再删)
    String jobInstanceSubquery = "SELECT id FROM batch.job_instance WHERE job_code LIKE :p";

    // 1) workflow 运行态
    result.put(
        "workflow_node_run",
        jdbc.update(
            "DELETE FROM batch.workflow_node_run WHERE workflow_run_id IN ("
                + "SELECT id FROM batch.workflow_run WHERE workflow_definition_id IN ("
                + "SELECT id FROM batch.workflow_definition WHERE workflow_code LIKE :p)"
                + " OR related_job_instance_id IN ("
                + jobInstanceSubquery
                + "))",
            new MapSqlParameterSource("p", like)));
    result.put(
        "workflow_run",
        jdbc.update(
            "DELETE FROM batch.workflow_run WHERE workflow_definition_id IN ("
                + "SELECT id FROM batch.workflow_definition WHERE workflow_code LIKE :p)"
                + " OR related_job_instance_id IN ("
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
            "DELETE FROM batch.job_instance WHERE job_code LIKE :p",
            new MapSqlParameterSource("p", like)));

    // 5) workflow 定义态
    result.put(
        "workflow_node",
        jdbc.update(
            "DELETE FROM batch.workflow_node WHERE workflow_definition_id IN ("
                + "SELECT id FROM batch.workflow_definition WHERE workflow_code LIKE :p)",
            new MapSqlParameterSource("p", like)));
    result.put(
        "workflow_edge",
        jdbc.update(
            "DELETE FROM batch.workflow_edge WHERE workflow_definition_id IN ("
                + "SELECT id FROM batch.workflow_definition WHERE workflow_code LIKE :p)",
            new MapSqlParameterSource("p", like)));
    result.put(
        "workflow_definition",
        jdbc.update(
            "DELETE FROM batch.workflow_definition WHERE workflow_code LIKE :p",
            new MapSqlParameterSource("p", like)));

    // 6) job_definition:trigger_runtime_state 是 CASCADE,无需显式
    result.put(
        "job_definition",
        jdbc.update(
            "DELETE FROM batch.job_definition WHERE job_code LIKE :p",
            new MapSqlParameterSource("p", like)));
    result.put(
        "file_channel_config",
        jdbc.update(
            "DELETE FROM batch.file_channel_config WHERE channel_code LIKE :p",
            new MapSqlParameterSource("p", like)));
    result.put(
        "file_template_config",
        jdbc.update(
            "DELETE FROM batch.file_template_config WHERE template_code LIKE :p",
            new MapSqlParameterSource("p", like)));
    result.put(
        "console_user_account",
        jdbc.update(
            "DELETE FROM batch.console_user_account WHERE username LIKE :p OR username LIKE :op",
            new MapSqlParameterSource("p", like).addValue("op", opLike)));
    result.put(
        "archive_policy",
        jdbc.update(
            "DELETE FROM batch.archive_policy WHERE tenant_id LIKE :p",
            new MapSqlParameterSource("p", like)));
    result.put(
        "tenant",
        jdbc.update(
            "DELETE FROM batch.tenant WHERE tenant_id LIKE :p",
            new MapSqlParameterSource("p", like)));

    int totalDeleted = result.values().stream().mapToInt(Integer::intValue).sum();
    log.info(
        "[admin] test-data cleanup prefix={} totalDeleted={} breakdown={}",
        prefix,
        totalDeleted,
        result);
    return result;
  }
}
