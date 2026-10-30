package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_QUEUE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_WORKER_GROUP;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 应用配置包时校验资源分配与运行监控设置由平台管理。 */
@Component
public class TenantConfigPackageExcelApplyAuthorization {

  private static final Set<String> PLATFORM_MONITORING_FIELDS = Set.of(
      COL_SOFT_RUNTIME_SECONDS,
      COL_SOFT_RUNTIME_SEVERITY,
      COL_START_GRACE_SECONDS,
      COL_START_GRACE_SEVERITY);

  public void assertAllowed(PackageValidationResult result) {
    ConsolePrincipal principal = currentPrincipal();
    Set<String> authorities = principal.authorities();
    if (authorities.contains(ConsoleRoles.ADMIN)) {
      return;
    }
    if (!authorities.contains(ConsoleRoles.TENANT_ADMIN)) {
      throw forbidden();
    }
    boolean packageContainsPlatformManagedFields =
        EmptyChecks.isNotEmpty(result.validResourceQueues())
            || hasValues(result.validJobs(), Set.of(COL_QUEUE_CODE, COL_WORKER_GROUP))
            || hasValues(result.validWfNodes(), Set.of(COL_WORKER_GROUP))
            || hasValues(result.validJobMonitoringPolicies(), PLATFORM_MONITORING_FIELDS);
    if (packageContainsPlatformManagedFields) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.config_package.platform_fields_forbidden");
    }
  }

  public boolean canViewPlatformFields() {
    return ConsoleRoles.hasGlobalRole(currentPrincipal().authorities());
  }

  private static ConsolePrincipal currentPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null // empty-check: allow - Sonar S2259
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal;
    }
    throw forbidden();
  }

  private static boolean hasValues(List<Map<String, String>> rows, Set<String> fields) {
    return rows.stream()
        .anyMatch(row -> fields.stream()
            .map(row::get)
            .anyMatch(value -> EmptyChecks.isNotNull(value) && EmptyChecks.isNotEmpty(value)));
  }

  private static BizException forbidden() {
    return BizException.of(ResultCode.FORBIDDEN, "error.config_package.apply_forbidden");
  }
}
