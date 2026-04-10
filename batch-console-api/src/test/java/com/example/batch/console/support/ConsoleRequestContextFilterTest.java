package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.console.support.ConsoleSecurityResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

class ConsoleRequestContextFilterTest {

    private ConsoleRequestContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ConsoleRequestContextFilter(new ConsoleSecurityResponseWriter(new ObjectMapper()));
        ReflectionTestUtils.setField(filter, "applicationName", "batch-console-api");
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectTenantMismatchWhenAuthenticatedPrincipalIsPresent() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("alice", "tenant-a", Set.of("ROLE_ADMIN")),
                "secret",
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        MockHttpServletRequest request = baseRequest();
        request.addHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER, "tenant-b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, noOpChain(chainCalled));
        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains(ResultCode.FORBIDDEN.name());
        assertThat(response.getContentAsString()).contains(CommonErrorMessages.TENANT_MISMATCH);
    }

    private MockHttpServletRequest baseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/console/jobs");
        request.addHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, "req-001");
        request.addHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-001");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private FilterChain noOpChain(AtomicBoolean chainCalled) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                chainCalled.set(true);
            }
        };
    }
}
