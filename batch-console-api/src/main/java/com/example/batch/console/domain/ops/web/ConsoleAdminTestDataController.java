package com.example.batch.console.domain.ops.web;

import com.example.batch.common.config.BatchProfileSupport;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.domain.ops.service.ConsoleAdminTestDataCleanupService;
import com.example.batch.console.service.ConsoleResponseFactory;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
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
 *   <li>prefix 强制 `^[a-zA-Z][a-zA-Z0-9_-]{2,32}$` 正则:必须字母开头、3-33 字符、只能字母数字下划线连字符。 禁止
 *       `'`/`;`/`%`/`_`/`\` 等 SQL 通配符 + 防注入。**不接受空 prefix** —— 防止删全库
 *   <li>**只匹配 `prefix-...`** (prefix + 连字符)而不是 `prefix...`,避免误删合法资源(如 prefix='test' 不会误删 `tester`
 *       这种正常用户)
 *   <li>清理路径覆盖 11 张业务表(含关联子表),按 FK 反向 DELETE,跑在 service 的 @Transactional 里要么全成要么全回滚
 * </ul>
 *
 * <p>事务边界在 {@link ConsoleAdminTestDataCleanupService}(CLAUDE.md §Java 编码细则 #4 禁 @Transactional 放
 * Controller),本 Controller 仅负责入口校验 + 委派。
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

  // 禁用 `_`:SQL LIKE 中 `_` 是单字符通配符,管理员删除接口误删风险高,严格规避。
  // 改用 hyphen 作为分隔(本就是文档示例: `e2e-suite-A`)。
  private static final String PREFIX_PATTERN = "^[a-zA-Z][a-zA-Z0-9-]{2,32}$";

  private final ConsoleAdminTestDataCleanupService cleanupService;
  private final ConsoleResponseFactory responseFactory;
  private final Environment environment;

  /**
   * 启动 fail-fast:prod profile 直接拒绝实例化此 controller(双保险);非 prod 仅 ROLE_ADMIN 可调。 若运维需要在生产 emergency
   * 清理,请走 SOP + DBA 直连,不要打开此端点。
   */
  @PostConstruct
  void validateProfile() {
    if (BatchProfileSupport.isProductionProfile(environment)) {
      throw new IllegalStateException(
          "ConsoleAdminTestDataController 不允许在生产 profile 启用 — 移除 active profile 或换 dev/test/local");
    }
  }

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
              message = "prefix 必须字母开头,3-33 字符,只能含字母/数字/-(禁 _,SQL LIKE 单字符通配符)")
          String prefix) {
    if (prefix == null || prefix.isBlank()) {
      // Spring validation 已拦,这里再硬挡一次,防止反射调用绕过 @Pattern
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    Map<String, Integer> result = cleanupService.cleanupByPrefix(prefix);
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
          @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_,-]{2,255}$", message = "ids 必须字母开头,逗号分隔,长度 3-256")
          String ids) {
    if (ids == null || ids.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    List<String> tenantIds =
        Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    Map<String, Integer> result = cleanupService.cleanupByExactTenantIds(tenantIds);
    return responseFactory.success(result);
  }
}
