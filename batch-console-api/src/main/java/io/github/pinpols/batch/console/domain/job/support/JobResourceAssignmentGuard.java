package io.github.pinpols.batch.console.domain.job.support;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 将物理队列和 Worker 分组的分配权保留给平台运维。 */
@Component
public class JobResourceAssignmentGuard {

  private static final String ERROR_KEY = "error.job.resource_assignment_platform_owned";

  public void assertCreateAllowed(String queueCode, String workerGroup) {
    if (isPlatformAdmin()) {
      return;
    }
    requireTenantAdmin();
    if (hasText(queueCode) || hasText(workerGroup)) {
      throw forbidden();
    }
  }

  public void assertUpdateAllowed(
      String requestedQueueCode,
      String requestedWorkerGroup,
      String currentQueueCode,
      String currentWorkerGroup) {
    if (isPlatformAdmin()) {
      return;
    }
    requireTenantAdmin();
    if (changes(requestedQueueCode, currentQueueCode, CodeNormalizer::toConfigFormOrNull)
        || changes(requestedWorkerGroup, currentWorkerGroup, CodeNormalizer::toUpperOrNull)) {
      throw forbidden();
    }
  }

  public void assertCopyOverridesAllowed(String queueCode, String workerGroup) {
    if (isPlatformAdmin()) {
      return;
    }
    requireTenantAdmin();
    if (hasText(queueCode) || hasText(workerGroup)) {
      throw forbidden();
    }
  }

  public boolean shouldResolveRoutingForCopy() {
    if (isPlatformAdmin()) {
      return false;
    }
    requireTenantAdmin();
    return true;
  }

  public boolean shouldPreserveExistingRouting() {
    if (isPlatformAdmin()) {
      return false;
    }
    requireTenantAdmin();
    return true;
  }

  private static boolean changes(
      String requested, String current, Function<String, String> normalize) {
    return EmptyChecks.isNotNull(requested)
        && !Objects.equals(normalize.apply(requested), normalize.apply(current));
  }

  private static boolean isPlatformAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return EmptyChecks.isNotNull(authentication)
        && authentication.isAuthenticated()
        && hasAuthority(authentication, ConsoleRoles.ADMIN);
  }

  private static void requireTenantAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (EmptyChecks.isNull(authentication)
        || !authentication.isAuthenticated()
        || !hasAuthority(authentication, ConsoleRoles.TENANT_ADMIN)) {
      throw forbidden();
    }
  }

  private static boolean hasAuthority(Authentication authentication, String authority) {
    return authentication.getAuthorities().stream()
        .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
  }

  private static boolean hasText(String value) {
    return EmptyChecks.isNotNull(value) && EmptyChecks.isNotEmpty(value);
  }

  private static BizException forbidden() {
    return BizException.of(ResultCode.FORBIDDEN, ERROR_KEY);
  }
}
