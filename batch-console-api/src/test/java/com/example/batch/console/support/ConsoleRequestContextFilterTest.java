package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.exception.BizException;
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

class ConsoleRequestContextFilterTest {

    private ConsoleRequestContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ConsoleRequestContextFilter();
        ReflectionTestUtils.setField(filter, "applicationName", "batch-console-api");
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectTenantMismatchWhenAuthenticatedPrincipalIsPresent() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("alice", "tenant-a", Set.of("ROLE_ADMIN")),
                "secret",
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        MockHttpServletRequest request = baseRequest();
        request.addHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER, "tenant-b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, noOpChain(chainCalled)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("tenant mismatch");

        assertThat(chainCalled).isFalse();
        assertThat(response.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER)).isNull();
        assertThat(response.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER)).isNull();
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
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                chainCalled.set(true);
            }
        };
    }
}
