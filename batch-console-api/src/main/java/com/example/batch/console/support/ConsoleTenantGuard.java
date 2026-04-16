package com.example.batch.console.support;

import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
