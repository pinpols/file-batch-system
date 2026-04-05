package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsoleTenantGuardTest {

    private final ConsoleRequestMetadataResolver requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    private final ConsoleTenantGuard tenantGuard = new ConsoleTenantGuard(requestMetadataResolver);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldResolveTenantFromRequestParameterWhenRequestScopeIsUnavailable() {
        when(requestMetadataResolver.current()).thenThrow(new IllegalStateException("request scope missing"));

        assertThat(tenantGuard.resolveTenant("tenant-a")).isEqualTo("tenant-a");
    }

    @Test
    void shouldPreferAuthenticatedTenantWhenRequestScopeIsUnavailable() {
        when(requestMetadataResolver.current()).thenThrow(new IllegalStateException("request scope missing"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("tester", "tenant-b", Set.of("ROLE_ADMIN")),
                "ignored"
        ));

        assertThat(tenantGuard.resolveTenant("tenant-b")).isEqualTo("tenant-b");
    }

    @Test
    void shouldRejectMissingTenantWhenRequestScopeAndParameterAreBothUnavailable() {
        when(requestMetadataResolver.current()).thenThrow(new IllegalStateException("request scope missing"));

        assertThatThrownBy(() -> tenantGuard.resolveTenant(" "))
                .isInstanceOf(BizException.class);
    }
}
