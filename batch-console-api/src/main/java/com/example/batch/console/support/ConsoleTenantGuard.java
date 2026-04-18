package com.example.batch.console.support;

import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 租户访问守卫：所有需要落租户维度的 console 操作经此解析/校验目标租户，返回归一化的 tenantId。
 *
 * <p>两条路径：
 *
 * <ul>
 *   <li><b>全局角色</b>（{@code ADMIN / AUDITOR / CONFIG_ADMIN}，见 {@link ConsoleRoles#hasGlobalRole}）：
 *       跨租户操作必须<b>显式</b>传 {@code requestTenantId}；为空直接 {@code INVALID_ARGUMENT} 拒绝
 *       ——防止全局角色因遗漏参数"默认当前租户"或"全量生效"造成意外越界。
 *   <li><b>租户角色</b>：以 JWT 里的 {@code tenantId} 为准，{@code requestTenantId} 非空时必须匹配，
 *       不匹配直接 {@code FORBIDDEN}——跨租户访问一律拒绝，即使是只读请求。
 * </ul>
 *
 * <p>Session 未激活（例如异步上下文）时 {@code ConsoleRequestMetadata} 读取会静默降级为 null，
 * 由上游兜底或抛 {@code UNAUTHORIZED}。
 */
@Component
@RequiredArgsConstructor
public class ConsoleTenantGuard {

  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  public String resolveTenant(String requestTenantId) {
    // 全局角色（ADMIN / AUDITOR / CONFIG_ADMIN）：必须显式指定目标租户
    if (isCurrentUserGlobal()) {
      if (requestTenantId == null || requestTenantId.isBlank()) {
        throw new BizException(ResultCode.INVALID_ARGUMENT, CommonErrorMessages.TENANT_REQUIRED);
      }
      return requestTenantId;
    }

    // 租户角色：原有逻辑，严格匹配
    ConsoleRequestMetadata metadata = currentMetadataOrNull();
    String authenticatedTenantId = authenticatedTenantId();
    String effectiveTenantId =
        authenticatedTenantId != null
            ? authenticatedTenantId
            : metadata != null ? metadata.tenantId() : null;
    if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
      effectiveTenantId = requestTenantId;
    }
    if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
      throw new BizException(ResultCode.UNAUTHORIZED, CommonErrorMessages.TENANT_REQUIRED);
    }
    if (requestTenantId != null
        && !requestTenantId.isBlank()
        && !requestTenantId.equals(effectiveTenantId)) {
      throw new BizException(ResultCode.FORBIDDEN, CommonErrorMessages.TENANT_MISMATCH);
    }
    return effectiveTenantId;
  }

  public void assertTenantAllowed(String requestedTenantId) {
    resolveTenant(requestedTenantId);
  }

  private ConsoleRequestMetadata currentMetadataOrNull() {
    try {
      return requestMetadataResolver.current();
    } catch (IllegalStateException exception) {
      return null;
    } catch (ScopeNotActiveException exception) {
      return null;
    }
  }

  private boolean isCurrentUserGlobal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return ConsoleRoles.hasGlobalRole(principal.authorities());
    }
    return false;
  }

  private String authenticatedTenantId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof ConsolePrincipal principal)) {
      return null;
    }
    return principal.tenantId();
  }
}
