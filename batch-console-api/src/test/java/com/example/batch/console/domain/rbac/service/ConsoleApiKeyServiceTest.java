package com.example.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.rbac.entity.ApiKeyEntity;
import com.example.batch.console.domain.rbac.mapper.ConsoleApiKeyMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleApiKeyServiceTest {

  private ConsoleApiKeyMapper repository;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleApiKeyService service;

  @BeforeEach
  void setUp() {
    repository = mock(ConsoleApiKeyMapper.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    service = new ConsoleApiKeyService(repository, tenantGuard);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  void shouldListApiKeys() {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setId(1L);
    entity.setKeyName("my-key");
    when(repository.findAllByTenant("t1")).thenReturn(List.of(entity));

    List<ApiKeyEntity> result = service.list("t1");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getKeyName()).isEqualTo("my-key");
  }

  @Test
  void shouldReturnDetailById() {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setId(1L);
    entity.setKeyName("my-key");
    when(repository.findByTenantAndId("t1", 1L)).thenReturn(Optional.of(entity));

    ApiKeyEntity result = service.detail("t1", 1L);

    assertThat(result.getKeyName()).isEqualTo("my-key");
  }

  @Test
  void shouldThrowNotFoundWhenDetailMissing() {
    when(repository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail("t1", 99L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not_found");
  }

  @Test
  void shouldCreateApiKeyWithBkPrefix() {
    when(repository.findByTenantAndName("t1", "new-key")).thenReturn(Optional.empty());

    ApiKeyEntity created = new ApiKeyEntity();
    created.setId(1L);
    created.setKeyName("new-key");
    when(repository.findByTenantAndName("t1", "new-key"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(created));

    Instant expiresAt = BatchDateTimeSupport.utcNow().plusSeconds(86400);
    ConsoleApiKeyService.CreateResult result =
        service.create("t1", "new-key", "read", expiresAt, "admin");

    assertThat(result.rawKey()).startsWith("bk_");
    assertThat(result.entity().getKeyName()).isEqualTo("new-key");
    verify(repository)
        .insert(
            eq("t1"),
            eq("new-key"),
            anyString(),
            anyString(),
            anyString(),
            eq("pbkdf2"),
            eq("read"),
            eq(expiresAt),
            eq("admin"));
  }

  @Test
  void shouldThrowConflictWhenNameExists() {
    ApiKeyEntity existing = new ApiKeyEntity();
    existing.setKeyName("dup-key");
    when(repository.findByTenantAndName("t1", "dup-key")).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () -> service.create("t1", "dup-key", "read", BatchDateTimeSupport.utcNow(), "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("name_exists");
  }

  @Test
  void shouldRevokeApiKey() {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setId(1L);
    when(repository.findByTenantAndId("t1", 1L)).thenReturn(Optional.of(entity));

    service.revoke("t1", 1L, "admin");

    verify(repository).revoke("t1", 1L, "admin");
  }

  @Test
  void shouldThrowNotFoundWhenRevokeIdMissing() {
    when(repository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.revoke("t1", 99L, "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not_found");
  }
}
