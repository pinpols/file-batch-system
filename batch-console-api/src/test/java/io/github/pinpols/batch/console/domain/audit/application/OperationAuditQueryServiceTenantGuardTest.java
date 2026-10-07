package io.github.pinpols.batch.console.domain.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.page.CursorCodec;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper.AuditRow;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("操作审计查询服务: 租户隔离校验与游标分页行为")
class OperationAuditQueryServiceTenantGuardTest {

  @Mock
  private OperationAuditMapper mapper;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private ConsoleTenantGuard tenantGuard;
  private OperationAuditQueryService service;

  @BeforeEach
  void setUp() {
    tenantGuard = new ConsoleTenantGuard(requestMetadataResolver);
    service = new OperationAuditQueryService(mapper, tenantGuard, new ObjectMapper());
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    when(mapper.count(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0L);
    when(mapper.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("租户用户未带租户标识且认证无租户声明时拒绝查询")
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
  @DisplayName("租户用户请求租户与认证租户不一致时按越权拒绝")
  void shouldOverrideRequestTenantIdWithJwtTenantForTenantRole() {
    // 租户角色,JWT tenantId=ta;请求传 tb(越权尝试)→ FORBIDDEN
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", "ta", Set.of("ROLE_TENANT_USER")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("tb");
    assertThatThrownBy(() -> service.query(req)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户用户请求租户与认证租户一致时以该租户下传查询")
  void shouldPassResolvedTenantIdToMapperForTenantUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", "ta", Set.of("ROLE_TENANT_USER")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("ta"); // 一致即透传
    service.query(req);
    verify(mapper).count(eq("ta"), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(mapper)
        .query(eq("ta"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("全局管理员未指定目标租户时拒绝查询")
  void shouldRequireTenantIdForGlobalAdmin() {
    // 全局角色 ROLE_ADMIN 但未指定 tenantId → 必须明确目标租户
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId(null);
    assertThatThrownBy(() -> service.query(req)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("全局管理员显式指定租户时允许跨租户查询")
  void shouldAllowGlobalAdminToQueryAnyTenantExplicitly() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("tb"); // admin 跨租 OK
    service.query(req);
    verify(mapper).count(eq("tb"), any(), any(), any(), any(), any(), any(), any(), any(), any());
    assertThat(req.getTenantId()).isEqualTo("tb");
  }

  @Test
  @DisplayName("携带游标查询时跳过总数统计并按游标首页分页")
  void shouldUseCursorWithoutCountingForOperationAuditList() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", "ta", Set.of("ROLE_TENANT_USER")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(99);
    req.setPageSize(10);
    req.setTenantId("ta");
    req.setCursor(CursorCodec.encode(Map.of("id", 100L)));

    service.query(req);

    verify(mapper, never())
        .count(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(mapper)
        .query(
            eq("ta"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(100L),
            eq(new PageRequest(1, 10)));
  }

  @Test
  @DisplayName("空游标按游标首页处理且不触发总数统计")
  void shouldTreatEmptyCursorAsCursorFirstPage() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", "ta", Set.of("ROLE_TENANT_USER")), "x"));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(99);
    req.setPageSize(10);
    req.setTenantId("ta");
    req.setCursor("");

    service.query(req);

    verify(mapper, never())
        .count(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(mapper)
        .query(
            eq("ta"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(null),
            eq(new PageRequest(1, 10)));
  }

  @Test
  @DisplayName("返回历史审计记录前脱敏密码等凭据字段")
  void shouldRedactHistoricalCredentialsBeforeReturningAuditRows() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "x"));
    when(mapper.count(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1L);
    when(mapper.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(new AuditRow(
            1L,
            "ta",
            "user",
            "alice",
            "user.create",
            "admin",
            "ROLE_ADMIN",
            "SUCCESS",
            null,
            null,
            "{\"request\":{\"username\":\"alice\",\"password\":\"admin123\"}}",
            "trace-1",
            "request-1",
            null,
            null,
            1,
            Instant.parse("2026-09-21T00:00:00Z"))));
    OperationAuditQueryRequest req = new OperationAuditQueryRequest();
    req.setPageNo(1);
    req.setPageSize(10);
    req.setTenantId("ta");

    String params = service.query(req).items().getFirst().params();

    assertThat(params).contains("alice", "[REDACTED]").doesNotContain("admin123");
  }
}
