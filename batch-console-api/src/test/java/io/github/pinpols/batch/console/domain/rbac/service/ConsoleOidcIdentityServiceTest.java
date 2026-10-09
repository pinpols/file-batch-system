package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.ConsoleOidcIdentityRequest;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleOidcIdentityEntity;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleOidcIdentityMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

@DisplayName("OIDC 身份绑定服务")
class ConsoleOidcIdentityServiceTest {

  private static final String TENANT_ID = "tenant-a";
  private static final String ISSUER = "https://idp.example.com/issuer";

  private ConsoleOidcIdentityMapper identityMapper;
  private ConsoleUserAccountMapper userAccountMapper;
  private ConsoleOidcProperties properties;
  private ConsoleOidcIdentityService service;

  @BeforeEach
  void setUp() {
    identityMapper = mock(ConsoleOidcIdentityMapper.class);
    userAccountMapper = mock(ConsoleUserAccountMapper.class);
    properties = new ConsoleOidcProperties();
    properties.setEnabled(true);
    properties.setTenantId(TENANT_ID);
    properties.setIssuerUri(ISSUER);
    service = new ConsoleOidcIdentityService(identityMapper, userAccountMapper, properties);
  }

  @Test
  @DisplayName("列表只返回已配置租户的绑定")
  void shouldListIdentityBindingsForConfiguredTenant() {
    ConsoleOidcIdentityEntity entity = identity(7L, 42L, "operator", "subject-1");
    when(identityMapper.findByTenant(TENANT_ID)).thenReturn(List.of(entity));

    var result = service.list(TENANT_ID);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().username()).isEqualTo("operator");
    assertThat(result.getFirst().issuer()).isEqualTo(ISSUER);
    assertThat(result.getFirst().linkedBy()).isEqualTo("admin");
  }

  @Test
  @DisplayName("显式绑定现有启用账号，并使用平台配置的 issuer")
  void shouldBindSubjectToEnabledLocalAccount() {
    ConsoleUserAccountEntity account = account(42L, TENANT_ID, "operator", true);
    ConsoleOidcIdentityEntity saved = identity(7L, 42L, "operator", "subject-1");
    when(userAccountMapper.findByUsernameIgnoreCase("operator")).thenReturn(Optional.of(account));
    when(identityMapper.findByAccountAndTenant(42L, TENANT_ID)).thenReturn(Optional.of(saved));

    var response =
        service.bind(TENANT_ID, new ConsoleOidcIdentityRequest("operator", "subject-1"), "admin");

    assertThat(response.id()).isEqualTo(7L);
    assertThat(response.subject()).isEqualTo("subject-1");
    verify(identityMapper).insert(TENANT_ID, 42L, ISSUER, "subject-1", "admin");
  }

  @Test
  @DisplayName("不能绑定其他租户或已禁用的本地账号")
  void shouldRejectAccountOutsideEnabledTenant() {
    when(userAccountMapper.findByUsernameIgnoreCase("operator"))
        .thenReturn(Optional.of(account(42L, "tenant-b", "operator", true)));

    assertThatThrownBy(() -> service.bind(
            TENANT_ID, new ConsoleOidcIdentityRequest("operator", "subject-1"), "admin"))
        .isInstanceOf(BizException.class);

    verify(identityMapper, never()).insert(TENANT_ID, 42L, ISSUER, "subject-1", "admin");
  }

  @Test
  @DisplayName("拒绝非 ASCII subject，避免不稳定的外部身份键")
  void shouldRejectNonAsciiSubject() {
    assertThatThrownBy(() ->
            service.bind(TENANT_ID, new ConsoleOidcIdentityRequest("operator", "用户"), "admin"))
        .isInstanceOf(BizException.class);

    verify(userAccountMapper, never()).findByUsernameIgnoreCase("operator");
  }

  @Test
  @DisplayName("唯一键冲突转换为稳定业务冲突")
  void shouldTranslateDuplicateBindingToConflict() {
    when(userAccountMapper.findByUsernameIgnoreCase("operator"))
        .thenReturn(Optional.of(account(42L, TENANT_ID, "operator", true)));
    org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
        .when(identityMapper)
        .insert(TENANT_ID, 42L, ISSUER, "subject-1", "admin");

    assertThatThrownBy(() -> service.bind(
            TENANT_ID, new ConsoleOidcIdentityRequest("operator", "subject-1"), "admin"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("绑定写入后必须能读回，解绑必须精确命中租户记录")
  void shouldRequireReadAfterWriteAndTenantScopedUnbind() {
    when(userAccountMapper.findByUsernameIgnoreCase("operator"))
        .thenReturn(Optional.of(account(42L, TENANT_ID, "operator", true)));
    when(identityMapper.findByAccountAndTenant(42L, TENANT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.bind(
            TENANT_ID, new ConsoleOidcIdentityRequest("operator", "subject-1"), "admin"))
        .isInstanceOf(BizException.class);

    when(identityMapper.deleteByIdAndTenant(7L, TENANT_ID)).thenReturn(1);
    service.unbind(TENANT_ID, 7L);
    verify(identityMapper).deleteByIdAndTenant(7L, TENANT_ID);
  }

  @Test
  @DisplayName("OIDC 未启用或解绑未命中时返回不可用/不存在")
  void shouldRejectUnavailableTenantAndMissingBinding() {
    properties.setEnabled(false);
    assertThatThrownBy(() -> service.list(TENANT_ID)).isInstanceOf(BizException.class);

    properties.setEnabled(true);
    when(identityMapper.deleteByIdAndTenant(7L, TENANT_ID)).thenReturn(0);
    assertThatThrownBy(() -> service.unbind(TENANT_ID, 7L)).isInstanceOf(BizException.class);
  }

  private ConsoleOidcIdentityEntity identity(
      long id, long accountId, String username, String subject) {
    ConsoleOidcIdentityEntity entity = new ConsoleOidcIdentityEntity();
    entity.setId(id);
    entity.setTenantId(TENANT_ID);
    entity.setAccountId(accountId);
    entity.setUsername(username);
    entity.setIssuer(ISSUER);
    entity.setSubject(subject);
    entity.setLinkedBy("admin");
    entity.setLinkedAt(Instant.parse("2026-10-09T00:00:00Z"));
    return entity;
  }

  private static ConsoleUserAccountEntity account(
      long id, String tenantId, String username, boolean enabled) {
    ConsoleUserAccountEntity account = new ConsoleUserAccountEntity();
    account.setId(id);
    account.setTenantId(tenantId);
    account.setUsername(username);
    account.setEnabled(enabled);
    return account;
  }
}
