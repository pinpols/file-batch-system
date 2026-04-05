package com.example.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.support.ConsoleAuthenticationFilter;
import com.example.batch.console.support.ConsoleJwtService;
import com.example.batch.console.support.ConsoleSessionRegistry;
import com.example.batch.console.support.ConsolePrincipal;
import com.example.batch.console.support.ConsoleSecurityResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsoleSecurityConfigurationTest {

    private ConsoleSecurityProperties properties;
    private BatchSecurityProperties batchSecurityProperties;
    private ConsoleAuthenticationFilter filter;
    private ConsoleSessionRegistry sessionRegistry;
    private ConsoleJwtService jwtService;

    @BeforeEach
    void setUp() {
        properties = new ConsoleSecurityProperties();
        properties.setEnabled(true);
        properties.setSharedSecret("console-secret");
        properties.setJwtIssuer("batch-console-api");
        properties.setJwtSecret("console-jwt-secret");
        properties.setDefaultTenantId("tenant-a");
        properties.setAllowedTenants(new ArrayList<>(List.of("tenant-a")));
        properties.setDefaultAuthorities(new ArrayList<>(List.of("ROLE_ADMIN", "ROLE_AUDITOR")));

        batchSecurityProperties = new BatchSecurityProperties();
        batchSecurityProperties.setTestingOpen(false);
        sessionRegistry = Mockito.mock(ConsoleSessionRegistry.class);
        jwtService = new ConsoleJwtService(properties, sessionRegistry);
        filter = new ConsoleAuthenticationFilter(
                properties,
                batchSecurityProperties,
                jwtService,
                new ConsoleSecurityResponseWriter(new ObjectMapper())
        );
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectInvalidLegacyTokenWhenTestingOpenIsDisabled() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader(properties.getTokenHeader(), "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, noOpChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(readBody(response)).contains("\"code\":\"UNAUTHORIZED\"");
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

        filter.doFilter(request, response, noOpChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(readBody(response)).contains("\"code\":\"FORBIDDEN\"");
        assertThat(chainCalled).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldAuthenticateWithLegacyTokenWhenTokenIsValid() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader(properties.getTokenHeader(), properties.getSharedSecret());
        request.addHeader(properties.getUserHeader(), "alice");
        request.addHeader(properties.getRoleHeader(), "ROLE_ADMIN,ROLE_AUDITOR");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) {
                chainCalled.set(true);
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
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

    @Test
    void shouldAuthenticateAsAdminInDemoModeWithoutToken() throws Exception {
        batchSecurityProperties.setDemoOpen(true);
        MockHttpServletRequest request = baseRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) {
                chainCalled.set(true);
                com.example.batch.console.support.ConsolePrincipal principal =
                        (com.example.batch.console.support.ConsolePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                assertThat(principal.authorities()).contains("ROLE_ADMIN");
            }
        });

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldAuthenticateWithJwtBearerToken() throws Exception {
        String token = jwtService.issueToken("bob", "tenant-a", Set.of("ROLE_ADMIN"), 9L).accessToken();
        Mockito.when(sessionRegistry.isCurrentSession("bob", "tenant-a", 9L)).thenReturn(true);

        MockHttpServletRequest request = baseRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) {
                chainCalled.set(true);
                com.example.batch.console.support.ConsolePrincipal principal =
                        (com.example.batch.console.support.ConsolePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                assertThat(principal.username()).isEqualTo("bob");
                assertThat(principal.tenantId()).isEqualTo("tenant-a");
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
        request.addHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, "req-001");
        request.addHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-001");
        request.setRemoteAddr("127.0.0.1");
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

    private String readBody(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
