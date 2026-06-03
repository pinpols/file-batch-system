package com.example.batch.console.domain.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.audit.mapper.OperationAuditMapper;
import com.example.batch.console.domain.audit.web.query.OperationAuditQueryRequest;
import com.example.batch.console.domain.rbac.support.ConsolePrincipal;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * P0 回归:OperationAuditQueryService 必须经 ConsoleTenantGuard.resolveTenant 解析后下发 mapper, 否则租户用户传
 * null/blank 即可绕过 SQL 租户过滤拿全租户审计。
 */
// LENIENT:setUp 共享 stub(mapper.count/query)被 reject 类用例不触发,符合 §测试约定豁免。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperationAuditQueryServiceTenantGuardTest {

  @Mock private OperationAuditMapper mapper;
  @Mock private ConsoleRequestMetadataResolver requestMetadataResolver;
  private ConsoleTenantGuard tenantGuard;
  private OperationAuditQueryService service;

  @BeforeEach
  void setUp() {
    tenantGuard = new ConsoleTenantGuard(requestMetadataResolver);
    service = new OperationAuditQueryService(mapper, tenantGuard);
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    when(mapper.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0L);
    when(mapper.query(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldRejectTenantUserPassingNullTenantId() {
    // 租户角色,JWT 未注入,requestTenantId=null → guard 抛 UNAUTHORIZED / required
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("anon", "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId(null);
    assertThatThrownBy(() -> service.query(req)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldOverrideRequestTenantIdWithJwtTenantForTenantRole() {
    // 租户角色,JWT tenantId=ta;请求传 tb(越权尝试)→ FORBIDDEN
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("tester", "ta", Set.of("ROLE_TENANT_USER")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("tb");
    assertThatThrownBy(() -> service.query(req)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldPassResolvedTenantIdToMapperForTenantUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("tester", "ta", Set.of("ROLE_TENANT_USER")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("ta"); // 一致即透传
    service.query(req);
    verify(mapper).count(eq("ta"), any(), any(), any(), any(), any(), any(), any(), any());
    verify(mapper)
        .query(
            eq("ta"), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void shouldRequireTenantIdForGlobalAdmin() {
    // 全局角色 ROLE_ADMIN 但未指定 tenantId → 必须明确目标租户
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId(null);
    assertThatThrownBy(() -> service.query(req)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldAllowGlobalAdminToQueryAnyTenantExplicitly() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("tb"); // admin 跨租 OK
    service.query(req);
    verify(mapper).count(eq("tb"), any(), any(), any(), any(), any(), any(), any(), any());
    assertThat(req.getTenantId()).isEqualTo("tb");
  }
}
