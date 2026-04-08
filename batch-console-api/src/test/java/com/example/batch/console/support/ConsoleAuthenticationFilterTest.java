package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.console.config.ConsoleSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleAuthenticationFilterTest {

    @Mock
    private ConsoleJwtService jwtService;
    @Mock
    private ConsoleSecurityResponseWriter responseWriter;

    private ConsoleSecurityProperties properties;
    private BatchSecurityProperties batchProperties;

    private ConsoleAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        properties = new ConsoleSecurityProperties();
        properties.setEnabled(true);
        properties.setSharedSecret("secret");
        properties.setLegacyHeaderAuthEnabled(true);
        properties.setDefaultTenantId("default-tenant");
        properties.setAllowedTenants(List.of("default-tenant", "t1"));
        properties.setDefaultAuthorities(List.of("ROLE_ADMIN"));

        batchProperties = new BatchSecurityProperties();

        filter = new ConsoleAuthenticationFilter(properties, batchProperties, jwtService, responseWriter);
        SecurityContextHolder.clearContext();
    }

    @Test
    void filter_passesThroughWhenAuthDisabledAndNotTestingOrDemo() throws Exception {
        properties.setEnabled(false);
        batchProperties.setTestingOpen(false);
        batchProperties.setDemoOpen(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void filter_setsDemoAuthAndContinuesWhenDemoOpen() throws Exception {
        batchProperties.setDemoOpen(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(properties.getTenantHeader(), "t1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        // SecurityContext should be cleared in finally block
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void filter_authenticatesViaBearerToken() throws Exception {
        ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", java.util.Set.of("ROLE_ADMIN"));
        when(jwtService.authenticate("valid-jwt")).thenReturn(principal);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(jwtService).authenticate("valid-jwt");
    }

    @Test
    void filter_returns401WhenBearerTokenInvalid() throws Exception {
        when(jwtService.authenticate(anyString())).thenThrow(new RuntimeException("expired"));
        doNothing().when(responseWriter).write(any(), any(), any(), anyString());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(responseWriter).write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void filter_authenticatesViaLegacySharedTokenHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(properties.getTokenHeader(), "secret");
        request.addHeader(properties.getTenantHeader(), "t1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(jwtService, never()).authenticate(anyString());
    }

    @Test
    void filter_returns401WhenLegacySharedTokenMismatch() throws Exception {
        doNothing().when(responseWriter).write(any(), any(), any(), anyString());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(properties.getTokenHeader(), "wrong-secret");
        request.addHeader(properties.getTenantHeader(), "t1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(responseWriter).write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void filter_returns403WhenTenantNotAllowed() throws Exception {
        doNothing().when(responseWriter).write(any(), any(), any(), anyString());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(properties.getTokenHeader(), "secret");
        request.addHeader(properties.getTenantHeader(), "not-allowed-tenant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(responseWriter).write(eq(response), eq(HttpStatus.FORBIDDEN), any(), anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void filter_passesThroughWhenTestingOpenAndNoToken() throws Exception {
        batchProperties.setTestingOpen(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(properties.getTenantHeader(), "t1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void filter_resolvesBearerTokenFromQueryParam() throws Exception {
        ConsolePrincipal principal = new ConsolePrincipal("bob", "t1", java.util.Set.of("ROLE_ADMIN"));
        when(jwtService.authenticate("query-jwt")).thenReturn(principal);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", "query-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(jwtService).authenticate("query-jwt");
        verify(chain).doFilter(request, response);
    }

    @Test
    void filter_clearSecurityContextInFinally() throws Exception {
        batchProperties.setTestingOpen(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(properties.getTenantHeader(), "t1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
