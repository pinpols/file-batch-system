package com.example.batch.console.domain.audit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.batch.console.domain.audit.mapper.OperationAuditMapper;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
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
}
