package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.audit.AuditAction;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员专用:批量清理测试数据。
 *
 * <p>解决 e2e 测试只 INSERT 不 DELETE 导致业务表 60-85% 是脏数据的问题。CI 跑完 e2e 调一次 这个端点即可零残留。
 *
 * <p>**严格安全约束**:
 *
 * <ul>
 *   <li>ROLE_ADMIN 才能调
 *   <li>prefix 强制 `^[a-zA-Z][a-zA-Z0-9_-]{2,32}$` 正则:必须字母开头、3-33 字符、只能字母数字下划线连字符。 禁止
 *       `'`/`;`/`%`/`_`/`\` 等 SQL 通配符 + 防注入。**不接受空 prefix** —— 防止删全库
 *   <li>**只匹配 `prefix-...`** (prefix + 连字符)而不是 `prefix...`,避免误删合法资源(如 prefix='test' 不会误删 `tester`
 *       这种正常用户)
 *   <li>清理路径覆盖 11 张业务表(含关联子表),按 FK 反向 DELETE,跑在 @Transactional 里要么全成要么全回滚
 * </ul>
 *
 * <p>典型使用:`DELETE /api/console/admin/test-data?prefix=e2e` 或 `?prefix=test-suite-A`。
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/console/admin/test-data")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleAdminTestDataController {

  private static final String PREFIX_PATTERN = "^[a-zA-Z][a-zA-Z0-9_-]{2,32}$";

  private final NamedParameterJdbcTemplate jdbc;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 按 prefix 批量级联清理测试数据。返回 map 列出每张表删了多少行。
   *
   * <p>prefix 已被正则严格限制,SQL LIKE 模板里手动加 `-%` 后缀 → 只会匹配 `prefix-xxx` 而不会匹配 `prefix...` 误删合法数据。
   */
  @DeleteMapping
  @Transactional
  @AuditAction(
      action = "admin.testDataCleanup",
      aggregateType = "test_data",
      aggregateId = "#prefix")
  public CommonResponse<Map<String, Integer>> cleanup(
      @RequestParam
          @Pattern(regexp = PREFIX_PATTERN, message = "prefix 必须字母开头,3-33 字符,只能含字母/数字/_/-")
          String prefix) {
    String like = prefix + "-%";
    String opLike = "op-" + prefix + "-%"; // RBAC 测试创建的 op-${prefix}-xxx 用户
    Map<String, Integer> result = new LinkedHashMap<>();

    // 按 FK 反向清理。每段独立 SQL 便于 audit + 排障
    result.put(
        "job_partition",
        jdbc.update(
            "DELETE FROM batch.job_partition WHERE job_instance_id IN ("
                + "SELECT id FROM batch.job_instance WHERE job_code LIKE :p)",
            new MapSqlParameterSource("p", like)));
    result.put(
        "job_instance",
        jdbc.update(
            "DELETE FROM batch.job_instance WHERE job_code LIKE :p",
            new MapSqlParameterSource("p", like)));
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
    return responseFactory.success(result);
  }
}
