package io.github.pinpols.batch.console.domain.audit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePrincipal;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 守护:console_operation_audit.tenant_id NOT NULL。auth.login / auth.logout 等系统级动作
 * principal.tenantId() 为 null 时,AuditAspect 必须 fallback 到 MDC tenant 或 "system", 否则 PSQLException
 * violates not-null constraint,审计行直接丢失。
 */
class AuditAspectTenantFallbackTest {

  private OperationAuditMapper mapper;
  private AuditAspect aspect;

  @AuditAction(aggregateType = "auth", aggregateId = "-", action = "auth.logout")
  public void sampleAuthLogout() {
    // 仅作为反射目标方法
  }

  /**
   * 模拟 ROLE_ADMIN 跨租操作:{@code targetTenantParam} 指向方法参数 {@code tenantId},应当覆盖 principal.tenantId()
   * 的 null 以及 MDC,直接落到入参里的目标租户。
   */
  @AuditAction(
      aggregateType = "tenant",
      aggregateId = "#tenantId",
      action = "tenant.update",
      targetTenantParam = "#tenantId")
  public void sampleTenantUpdate(String tenantId) {
    // 仅作为反射目标方法
  }

  @BeforeEach
  void setUp() {
    mapper = mock(OperationAuditMapper.class);
    aspect = new AuditAspect(mapper, new ObjectMapper(), mock(PlatformTransactionManager.class));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  @Test
  void shouldFallbackToSystemSentinelWhenPrincipalAndMdcAreEmpty() throws Throwable {
    SecurityContextHolder.clearContext();

    ProceedingJoinPoint pjp = buildJoinPoint();
    aspect.wrap(pjp);

    ArgumentCaptor<OperationAuditEvent> captor = ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("system");
  }

  @Test
  void shouldFallbackToMdcTenantWhenPrincipalTenantIsNull() throws Throwable {
    MDC.put("tenant", "ten-x");
    ConsolePrincipal principal =
        new ConsolePrincipal("admin", null /* tenantId null */, Set.of("ROLE_ADMIN"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));

    ProceedingJoinPoint pjp = buildJoinPoint();
    aspect.wrap(pjp);

    ArgumentCaptor<OperationAuditEvent> captor = ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("ten-x");
  }

  @Test
  void shouldUsePrincipalTenantWhenPresent() throws Throwable {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "tenant-a", Set.of("ROLE_OPERATOR"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    MDC.put("tenant", "should-not-be-used");

    ProceedingJoinPoint pjp = buildJoinPoint();
    aspect.wrap(pjp);

    ArgumentCaptor<OperationAuditEvent> captor = ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
  }

  @Test
  void shouldUseTargetTenantParamForRoleAdminCrossTenantOperation() throws Throwable {
    // ROLE_ADMIN 改 "tenant-x":principal.tenantId() = null,但 targetTenantParam=#tenantId 指向入参
    // → audit 行 tenant_id 必须是 "tenant-x",而不是默认回退 "system",否则取证按目标租户查会漏。
    ConsolePrincipal principal =
        new ConsolePrincipal("root", null /* tenantId null */, Set.of("ROLE_ADMIN"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    MDC.put("tenant", "should-not-be-used");

    ProceedingJoinPoint pjp = buildTenantUpdateJoinPoint("tenant-x");
    aspect.wrap(pjp);

    ArgumentCaptor<OperationAuditEvent> captor = ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-x");
  }

  @Test
  void shouldStillFallbackWhenTargetTenantParamResolvesToNull() throws Throwable {
    // targetTenantParam=#tenantId 但入参传 null → 必须继续 principal → MDC → "system" 回退链
    ConsolePrincipal principal =
        new ConsolePrincipal("operator", "tenant-a", Set.of("ROLE_TENANT_OPERATOR"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));

    ProceedingJoinPoint pjp = buildTenantUpdateJoinPoint(null);
    aspect.wrap(pjp);

    ArgumentCaptor<OperationAuditEvent> captor = ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
  }

  private ProceedingJoinPoint buildJoinPoint() throws Throwable {
    Method m = getClass().getMethod("sampleAuthLogout");
    MethodSignature sig = mock(MethodSignature.class);
    org.mockito.Mockito.when(sig.getMethod()).thenReturn(m);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    org.mockito.Mockito.when(pjp.getSignature()).thenReturn((Signature) sig);
    org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[0]);
    org.mockito.Mockito.when(pjp.proceed()).thenReturn(null);
    return pjp;
  }

  private ProceedingJoinPoint buildTenantUpdateJoinPoint(String tenantIdArg) throws Throwable {
    Method m = getClass().getMethod("sampleTenantUpdate", String.class);
    MethodSignature sig = mock(MethodSignature.class);
    org.mockito.Mockito.when(sig.getMethod()).thenReturn(m);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    org.mockito.Mockito.when(pjp.getSignature()).thenReturn((Signature) sig);
    org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[] {tenantIdArg});
    org.mockito.Mockito.when(pjp.proceed()).thenReturn(null);
    return pjp;
  }
}
