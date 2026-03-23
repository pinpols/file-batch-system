package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleAiProperties;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConsoleAiAuthorizationService {

    private final ConsoleAiProperties properties;

    public void assertAllowed() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BizException(ResultCode.FORBIDDEN, "ai assistant requires authenticated user");
        }
        String username = authentication.getName();
        Set<String> authorities = authorities(authentication.getAuthorities());
        boolean allowedByUser = properties.getAllowedUsers().stream().anyMatch(user -> Objects.equals(user, username));
        boolean allowedByAuthority = properties.getAllowedAuthorities().stream().anyMatch(authorities::contains);
        if (!allowedByUser && !allowedByAuthority) {
            throw new BizException(ResultCode.FORBIDDEN, "ai assistant access is not granted");
        }
    }

    private Set<String> authorities(Collection<? extends GrantedAuthority> grantedAuthorities) {
        return grantedAuthorities == null ? Set.of() : grantedAuthorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
