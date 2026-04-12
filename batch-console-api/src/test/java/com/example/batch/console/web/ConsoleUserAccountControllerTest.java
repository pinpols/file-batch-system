package com.example.batch.console.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.service.ConsoleUserAccountService;
import com.example.batch.console.support.ConsolePasswordHasher;
import com.example.batch.console.support.ConsoleSessionRegistry;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleUserAccountControllerTest {

    @Mock private ConsoleUserAccountMapper userAccountMapper;
    @Mock private ConsolePasswordHasher passwordHasher;
    @Mock private ConsoleSessionRegistry sessionRegistry;

    private ConsoleUserAccountService service;

    private static final Map<String, Object> ACCOUNT = Map.of(
            "id", 42L,
            "tenant_id", "tenant-a",
            "username", "user-a",
            "display_name", "User A",
            "authorities_csv", "ROLE_TENANT_USER",
            "enabled", true,
            "created_at", "2026-01-01T00:00:00",
            "updated_at", "2026-01-01T00:00:00"
    );

    private static final Map<String, Object> DISABLED_ACCOUNT = Map.of(
            "id", 42L,
            "tenant_id", "tenant-a",
            "username", "user-a",
            "display_name", "User A",
            "authorities_csv", "ROLE_TENANT_USER",
            "enabled", false,
            "created_at", "2026-01-01T00:00:00",
            "updated_at", "2026-01-01T00:00:00"
    );

    @BeforeEach
    void setUp() {
        service = new ConsoleUserAccountService(userAccountMapper, passwordHasher, sessionRegistry);
    }

    @Test
    void disable_invalidatesSession() {
        when(userAccountMapper.selectById(42L)).thenReturn(ACCOUNT, DISABLED_ACCOUNT);

        service.disable(42L);

        verify(userAccountMapper).updateEnabled(42L, false);
        verify(sessionRegistry).invalidateSession("user-a", "tenant-a");
    }

    @Test
    void disable_nonExistentAccount_throwsBizException() {
        when(userAccountMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.disable(99L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("user account not found");

        verify(userAccountMapper, never()).updateEnabled(anyLong(), any(Boolean.class));
        verify(sessionRegistry, never()).invalidateSession(anyString(), anyString());
    }

    @Test
    void resetPassword_invalidatesSession() {
        when(userAccountMapper.selectById(42L)).thenReturn(ACCOUNT);
        when(passwordHasher.encode("newSecurePass")).thenReturn("$argon2id$...");

        service.resetPassword(42L, "newSecurePass");

        verify(userAccountMapper).updatePasswordHash(42L, "$argon2id$...");
        verify(sessionRegistry).invalidateSession("user-a", "tenant-a");
    }

    @Test
    void resetPassword_nonExistentAccount_throwsBizException() {
        when(userAccountMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword(99L, "newSecurePass"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("user account not found");

        verify(userAccountMapper, never()).updatePasswordHash(anyLong(), anyString());
        verify(sessionRegistry, never()).invalidateSession(anyString(), anyString());
    }

    @Test
    void enable_doesNotInvalidateSession() {
        when(userAccountMapper.selectById(42L)).thenReturn(ACCOUNT);

        service.enable(42L);

        verify(userAccountMapper).updateEnabled(42L, true);
        verify(sessionRegistry, never()).invalidateSession(anyString(), anyString());
    }
}
