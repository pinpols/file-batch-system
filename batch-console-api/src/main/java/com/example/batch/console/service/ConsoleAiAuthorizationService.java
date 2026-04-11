package com.example.batch.console.service;

import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleAiProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 控制台 AI 功能授权：按配置校验当前登录用户/角色是否允许使用 AI 助手。 */
@Service
@RequiredArgsConstructor
public class ConsoleAiAuthorizationService {

    private final ConsoleAiProperties properties;

    /** 未配置在白名单则抛出 FORBIDDEN。 */
    public void assertAllowed() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BizException(
                    ResultCode.FORBIDDEN,
                    CommonErrorMessages.AI_ASSISTANT_REQUIRES_AUTHENTICATED_USER);
        }
        String username = authentication.getName();
        Set<String> authorities = authorities(authentication.getAuthorities());
        boolean allowedByUser =
                properties.getAllowedUsers().stream()
                        .anyMatch(user -> Objects.equals(user, username));
        boolean allowedByAuthority =
                properties.getAllowedAuthorities().stream().anyMatch(authorities::contains);
        if (!allowedByUser && !allowedByAuthority) {
            throw new BizException(
                    ResultCode.FORBIDDEN, CommonErrorMessages.AI_ASSISTANT_ACCESS_NOT_GRANTED);
        }
    }

    private Set<String> authorities(Collection<? extends GrantedAuthority> grantedAuthorities) {
        return grantedAuthorities == null
                ? Set.of()
                : grantedAuthorities.stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
    }
}
