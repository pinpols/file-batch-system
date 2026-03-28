package com.example.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsoleSecurityConfigurationTest {

    private ConsoleSecurityProperties properties;
    private BatchSecurityProperties batchSecurityProperties;
    private ConsoleSecurityConfiguration.ConsoleTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        properties = new ConsoleSecurityProperties();
        properties.setEnabled(true);
        properties.setSharedSecret("console-secret");
        properties.setAllowedTenants(new ArrayList<>(List.of("tenant-a")));
        properties.setDefaultAuthorities(new ArrayList<>(List.of("ROLE_ADMIN", "ROLE_AUDITOR")));

        batchSecurityProperties = new BatchSecurityProperties();
        batchSecurityProperties.setTestingOpen(false);
        filter = new ConsoleSecurityConfiguration.ConsoleTokenAuthenticationFilter(properties, batchSecurityProperties);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectInvalidTokenWhenTestingOpenIsDisabled() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader(properties.getTokenHeader(), "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilterInternal(request, response, noOpChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getErrorMessage()).isEqualTo("invalid console token");
        assertThat(chainCalled).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldRejectTenantOutsideAllowedList() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader(properties.getTokenHeader(), properties.getSharedSecret());
        request.addHeader(properties.getTenantHeader(), "tenant-b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilterInternal(request, response, noOpChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getErrorMessage()).isEqualTo("tenant is not allowed");
        assertThat(chainCalled).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldUseDefaultTenantAndGrantAuthenticationWhenTokenIsValid() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader(properties.getTokenHeader(), properties.getSharedSecret());
        request.addHeader(properties.getUserHeader(), "alice");
        request.addHeader(properties.getRoleHeader(), "ROLE_ADMIN,ROLE_AUDITOR");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilterInternal(request, response, new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) {
                chainCalled.set(true);
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
                assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                        .isInstanceOf(com.example.batch.console.support.ConsolePrincipal.class);
                com.example.batch.console.support.ConsolePrincipal principal =
                        (com.example.batch.console.support.ConsolePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                assertThat(principal.username()).isEqualTo("alice");
                assertThat(principal.tenantId()).isEqualTo(properties.getDefaultTenantId());
            }
        });

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest baseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/console/ping");
        return request;
    }

    private FilterChain noOpChain(AtomicBoolean chainCalled) {
        return new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                chainCalled.set(true);
            }
        };
    }
}
