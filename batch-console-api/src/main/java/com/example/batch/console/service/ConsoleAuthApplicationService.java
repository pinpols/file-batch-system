package com.example.batch.console.service;

import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.support.ConsoleJwtService;
import com.example.batch.console.support.ConsoleLoginService;
import com.example.batch.console.support.ConsolePrincipal;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSessionRegistry;
import com.example.batch.console.web.request.ConsoleLoginRequest;
import com.example.batch.console.web.response.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.ConsoleAuthTokenResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ConsoleAuthApplicationService {

    private final ConsoleJwtService jwtService;
    private final ConsoleLoginService loginService;
    private final ConsoleSessionRegistry sessionRegistry;
    private final ConsoleSecurityProperties securityProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    public ConsoleAuthTokenResponse login(ConsoleLoginRequest request) {
        return loginService.login(request);
    }

    public ConsoleAuthTokenResponse issueToken(Authentication authentication) {
        String username = username(authentication);
        String tenantId = tenantId(authentication);
        long sessionVersion = sessionRegistry.nextSessionVersion(username, tenantId);
        return jwtService.issueToken(
                username, tenantId, authorities(authentication), sessionVersion);
    }

    public ConsoleAuthProfileResponse profile(Authentication authentication) {
        return new ConsoleAuthProfileResponse(
                username(authentication), tenantId(authentication), authorities(authentication));
    }

    private String username(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
            return principal.username();
        }
        if (authentication != null
                && authentication.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return authentication == null ? null : authentication.getName();
    }

    private String tenantId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
            return principal.tenantId();
        }
        String resolved = requestMetadataResolver.current().tenantId();
        return resolved == null || resolved.isBlank()
                ? securityProperties.getDefaultTenantId()
                : resolved;
    }

    private Set<String> authorities(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
            return principal.authorities();
        }
        Set<String> resolved = new LinkedHashSet<>();
        if (authentication != null) {
            for (GrantedAuthority grantedAuthority : authentication.getAuthorities()) {
                resolved.add(grantedAuthority.getAuthority());
            }
        }
        if (resolved.isEmpty() || resolved.contains("ROLE_USER")) {
            resolved.clear();
            resolved.addAll(securityProperties.getDefaultAuthorities());
        }
        return resolved;
    }
}
