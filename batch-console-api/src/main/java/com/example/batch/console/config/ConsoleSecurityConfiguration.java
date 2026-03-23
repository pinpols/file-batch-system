package com.example.batch.console.config;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.console.config.ConsoleSecurityProperties;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import com.example.batch.console.support.ConsolePrincipal;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class ConsoleSecurityConfiguration {

    private final ConsoleSecurityProperties properties;
    private final BatchSecurityProperties batchSecurityProperties;

    @Bean
    public SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new ConsoleTokenAuthenticationFilter(properties, batchSecurityProperties), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static class ConsoleTokenAuthenticationFilter extends OncePerRequestFilter {

        private final ConsoleSecurityProperties properties;
        private final BatchSecurityProperties batchSecurityProperties;

        ConsoleTokenAuthenticationFilter(ConsoleSecurityProperties properties, BatchSecurityProperties batchSecurityProperties) {
            this.properties = properties;
            this.batchSecurityProperties = batchSecurityProperties;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (!properties.isEnabled() && !batchSecurityProperties.isTestingOpen()) {
                filterChain.doFilter(request, response);
                return;
            }
            if (!batchSecurityProperties.isTestingOpen()) {
                String token = request.getHeader(properties.getTokenHeader());
                if (token == null || !token.equals(properties.getSharedSecret())) {
                    response.sendError(HttpStatus.UNAUTHORIZED.value(), "invalid console token");
                    return;
                }
            }
            String username = request.getHeader(properties.getUserHeader());
            if (username == null || username.isBlank()) {
                username = batchSecurityProperties.isTestingOpen() ? "testing-console-user" : "console-user";
            }
            String tenantId = request.getHeader(properties.getTenantHeader());
            if (tenantId != null && !tenantId.isBlank() && !properties.getAllowedTenants().isEmpty()
                    && !properties.getAllowedTenants().contains(tenantId)) {
                response.sendError(HttpStatus.FORBIDDEN.value(), "tenant is not allowed");
                return;
            }
            Set<SimpleGrantedAuthority> authorities = resolveAuthorities(request);
            ConsolePrincipal principal = new ConsolePrincipal(
                    username,
                    tenantId == null || tenantId.isBlank() ? properties.getDefaultTenantId() : tenantId,
                    authorities.stream().map(SimpleGrantedAuthority::getAuthority).collect(Collectors.toCollection(LinkedHashSet::new))
            );
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, batchSecurityProperties.isTestingOpen() ? "testing-open" : request.getHeader(properties.getTokenHeader()), authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private Set<SimpleGrantedAuthority> resolveAuthorities(HttpServletRequest request) {
            String rolesHeader = request.getHeader(properties.getRoleHeader());
            if (rolesHeader == null || rolesHeader.isBlank()) {
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
}
