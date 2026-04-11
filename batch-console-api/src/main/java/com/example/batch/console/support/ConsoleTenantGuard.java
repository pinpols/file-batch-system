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

    private String authenticatedTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof ConsolePrincipal principal)) {
            return null;
        }
        return principal.tenantId();
    }
}
