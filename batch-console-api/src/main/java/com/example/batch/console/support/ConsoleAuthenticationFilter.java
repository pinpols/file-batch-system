package com.example.batch.console.support;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.console.config.ConsoleSecurityProperties;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class ConsoleAuthenticationFilter extends OncePerRequestFilter {

    private final ConsoleSecurityProperties properties;
    private final BatchSecurityProperties batchSecurityProperties;
    private final ConsoleJwtService jwtService;
    private final ConsoleSecurityResponseWriter responseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            boolean demoOpen = batchSecurityProperties.isDemoOpen();
            if (!properties.isEnabled() && !batchSecurityProperties.isTestingOpen() && !demoOpen) {
                filterChain.doFilter(request, response);
                return;
            }

            if (demoOpen) {
                // Demo 模式下：无需携带 token/legacy header，默认以 admin 权限放行，便于前端联调。
                String username = resolveUsername(request);
                String tenantId = resolveTenant(request);
                Set<SimpleGrantedAuthority> authorities = properties.getDefaultAuthorities().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                ConsolePrincipal principal = new ConsolePrincipal(
                        username,
                        tenantId,
                        authorities.stream().map(SimpleGrantedAuthority::getAuthority).collect(Collectors.toCollection(LinkedHashSet::new))
                );
                setAuthentication(principal, "demo-open");
                filterChain.doFilter(request, response);
                return;
            }

            String bearerToken = resolveBearerToken(request);
            if (StringUtils.hasText(bearerToken)) {
                try {
                    ConsolePrincipal principal = jwtService.authenticate(bearerToken);
                    setAuthentication(principal, bearerToken);
                    filterChain.doFilter(request, response);
                    return;
                } catch (Exception exception) {
                    responseWriter.write(response, HttpStatus.UNAUTHORIZED, com.example.batch.common.enums.ResultCode.UNAUTHORIZED, CommonErrorMessages.INVALID_CONSOLE_JWT);
                    return;
                }
            }

            String sharedToken = request.getHeader(properties.getTokenHeader());
            if (properties.isLegacyHeaderAuthEnabled() && StringUtils.hasText(sharedToken)) {
                if (!batchSecurityProperties.isTestingOpen() && !sharedToken.equals(properties.getSharedSecret())) {
                    responseWriter.write(response, HttpStatus.UNAUTHORIZED, com.example.batch.common.enums.ResultCode.UNAUTHORIZED, CommonErrorMessages.INVALID_CONSOLE_TOKEN);
                    return;
                }
                try {
                    String username = resolveUsername(request);
                    String tenantId = resolveTenant(request);
                    Set<SimpleGrantedAuthority> authorities = resolveAuthorities(request);
                    ConsolePrincipal principal = new ConsolePrincipal(
                            username,
                            tenantId,
                            authorities.stream().map(SimpleGrantedAuthority::getAuthority).collect(Collectors.toCollection(LinkedHashSet::new))
                    );
                    setAuthentication(principal, sharedToken);
                    filterChain.doFilter(request, response);
                    return;
                } catch (IllegalArgumentException exception) {
                    responseWriter.write(response, HttpStatus.FORBIDDEN, com.example.batch.common.enums.ResultCode.FORBIDDEN, CommonErrorMessages.TENANT_MISMATCH);
                    return;
                }
            } else if (StringUtils.hasText(sharedToken)) {
                filterChain.doFilter(request, response);
                return;
            }

            if (batchSecurityProperties.isTestingOpen()) {
                try {
                    String username = resolveUsername(request);
                    String tenantId = resolveTenant(request);
                    Set<SimpleGrantedAuthority> authorities = resolveAuthorities(request);
                    ConsolePrincipal principal = new ConsolePrincipal(
                            username,
                            tenantId,
                            authorities.stream().map(SimpleGrantedAuthority::getAuthority).collect(Collectors.toCollection(LinkedHashSet::new))
                    );
                    setAuthentication(principal, "testing-open");
                } catch (IllegalArgumentException exception) {
                    responseWriter.write(response, HttpStatus.FORBIDDEN, com.example.batch.common.enums.ResultCode.FORBIDDEN, CommonErrorMessages.TENANT_MISMATCH);
                    return;
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void setAuthentication(ConsolePrincipal principal, String credentials) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        credentials,
                        principal.authorities().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toCollection(LinkedHashSet::new))
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        String queryToken = request.getParameter("token");
        return StringUtils.hasText(queryToken) ? queryToken.trim() : null;
    }

    private String resolveUsername(HttpServletRequest request) {
        String username = request.getHeader(properties.getUserHeader());
        if (!StringUtils.hasText(username)) {
            username = batchSecurityProperties.isTestingOpen() ? "testing-console-user" : "console-user";
        }
        return username;
    }

    private String resolveTenant(HttpServletRequest request) {
        String tenantId = request.getHeader(properties.getTenantHeader());
        if (!StringUtils.hasText(tenantId)) {
            tenantId = properties.getDefaultTenantId();
        }
        if (!properties.getAllowedTenants().isEmpty() && !properties.getAllowedTenants().contains(tenantId)) {
            throw new IllegalArgumentException("tenant not allowed");
        }
        return tenantId;
    }

    private Set<SimpleGrantedAuthority> resolveAuthorities(HttpServletRequest request) {
        String rolesHeader = request.getHeader(properties.getRoleHeader());
        if (!StringUtils.hasText(rolesHeader)) {
            return properties.getDefaultAuthorities().stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
