package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleAdminTestDataApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员专用:批量清理测试数据。
 *
 * <p>解决 e2e 测试只 INSERT 不 DELETE 导致业务表 60-85% 是异常数据的问题。CI 跑完 e2e 调一次 这个端点即可零残留。
 *
 * <p>**严格安全约束**:
 *
 * <ul>
 *   <li>ROLE_ADMIN 才能调
 *   <li>prefix 强制 `^[a-zA-Z][a-zA-Z0-9-]{2,32}$` 正则:必须字母开头、3-33 字符，只能使用字母、数字和连字符；拒绝 SQL
 *       通配符字符且不接受空值，避免误删全库
 *   <li>**只匹配 `prefix-...`** (prefix + 连字符)而不是 `prefix...`,避免误删合法资源(如 prefix='test' 不会误删 `tester`
 *       这种正常用户)
 *   <li>清理路径覆盖 11 张业务表(含关联子表),按 FK 反向 DELETE,跑在 service 的 @Transactional 里要么全成要么全回滚
 * </ul>
 *
 * <p>生产环境策略与精确 tenantId 解析由应用服务负责；实际级联清理和事务边界在 orchestrator 服务中。
 * 本 Controller 仅负责 HTTP 参数校验、权限注解、审计和响应封装。
 *
 * <p>典型使用:`DELETE /api/console/admin/test-data?prefix=e2e` 或 `?prefix=test-suite-A`。
 */
@RestController
@Validated
@RequestMapping("/api/console/admin/test-data")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
public class ConsoleAdminTestDataController {

  // 禁用 `_`:SQL LIKE 中 `_` 是单字符通配符,管理员删除接口误删风险高,严格规避。
  // 改用 hyphen 作为分隔(本就是文档示例: `e2e-suite-A`)。
  private static final String PREFIX_PATTERN = "^[a-zA-Z][a-zA-Z0-9-]{2,32}$";

  private final ConsoleAdminTestDataApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 按 prefix 批量级联清理测试数据。返回 map 列出每张表删了多少行。
   *
   * <p>prefix 已被正则严格限制,SQL LIKE 模板里手动加 `-%` 后缀 → 只会匹配 `prefix-xxx` 而不会匹配 `prefix...` 误删合法数据。
   */
  @DeleteMapping
  @AuditAction(
      action = "admin.testDataCleanup",
      aggregateType = "test_data",
      aggregateId = "#prefix")
  public CommonResponse<Map<String, Integer>> cleanup(
      @RequestParam
          @Pattern(
              regexp = PREFIX_PATTERN,
              message =
                  "prefix must start with a letter, contain 3-33 characters, and use only letters, digits, or hyphens (underscore is forbidden because it is a single-character SQL LIKE wildcard)")
          String prefix) {
    if (prefix == null || prefix.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    Map<String, Integer> result = applicationService.cleanupByPrefix(prefix);
    return responseFactory.success(result);
  }

  /**
   * 按精确 tenantId 列表清理(prefix 模式无法清纯短名 ID 如 `tx` / `td` 等历史残留时的补充处理)。
   *
   * <p>**白名单保护**:`system` / `default` / `default-tenant` / `ta` / `tb` / `tc` 任何场景拒删。
   *
   * <p>用法:`DELETE /api/console/admin/test-data/by-ids?ids=td,te,tf`
   */
  @DeleteMapping("/by-ids")
  @AuditAction(
      action = "admin.testDataCleanupByIds",
      aggregateType = "test_data",
      aggregateId = "#ids")
  public CommonResponse<Map<String, Integer>> cleanupByExactIds(
      @RequestParam
          @Pattern(
              regexp = "^[a-zA-Z][a-zA-Z0-9_,-]{2,255}$",
              message =
                  "ids must start with a letter, be comma-separated, and contain 3-256 characters")
          String ids) {
    if (ids == null || ids.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    Map<String, Integer> result = applicationService.cleanupByExactIds(ids);
    return responseFactory.success(result);
  }
}
