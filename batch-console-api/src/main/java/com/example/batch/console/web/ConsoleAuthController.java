package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleJwtService;
import com.example.batch.console.support.ConsolePrincipal;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.ConsoleAuthTokenResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/auth")
@RequiredArgsConstructor
public class ConsoleAuthController {

    private final ConsoleJwtService jwtService;
    private final ConsoleResponseFactory responseFactory;
    private final ConsoleSecurityProperties securityProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    @PostMapping("/token")
    @PreAuthorize("isAuthenticated()")
    public CommonResponse<ConsoleAuthTokenResponse> token() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        ConsoleAuthTokenResponse response = jwtService.issueToken(
                username(authentication),
                tenantId(authentication),
                authorities(authentication)
        );
        return responseFactory.success(response);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public CommonResponse<ConsoleAuthProfileResponse> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return responseFactory.success(new ConsoleAuthProfileResponse(
                username(authentication),
                tenantId(authentication),
                authorities(authentication)
        ));
    }

    private String username(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
            return principal.username();
        }
        if (authentication != null && authentication.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return authentication == null ? null : authentication.getName();
    }

    private String tenantId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
            return principal.tenantId();
        }
        String resolved = requestMetadataResolver.current().tenantId();
        return resolved == null || resolved.isBlank() ? securityProperties.getDefaultTenantId() : resolved;
    }

    private Set<String> authorities(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
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
