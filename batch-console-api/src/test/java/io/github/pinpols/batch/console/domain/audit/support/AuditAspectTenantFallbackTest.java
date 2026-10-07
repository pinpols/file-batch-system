package io.github.pinpols.batch.console.domain.audit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.shared.usage.ConsoleUsageRecorder;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 守护:console_operation_audit.tenant_id NOT NULL。auth.login / auth.logout 等系统级动作
 * principal.tenantId() 为 null 时,AuditAspect 必须 fallback 到 MDC tenant 或 "system", 否则 PSQLException
 * violates not-null constraint,审计行直接丢失。
 */
@DisplayName("审计切面租户回退: 主体、请求上下文与目标参数的取值优先级")
class AuditAspectTenantFallbackTest {

  private OperationAuditMapper mapper;
  private ConsoleUsageRecorder usageRecorder;
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
    usageRecorder = mock(ConsoleUsageRecorder.class);
    aspect = new AuditAspect(
        mapper, new ObjectMapper(), mock(PlatformTransactionManager.class), usageRecorder);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    MDC.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  @DisplayName("审计事务提交后才记录用量, 提交前不发生调用")
  void shouldRecordUsageOnlyAfterAuditTransactionCommits() throws Throwable {
    TransactionSynchronizationManager.initSynchronization();
    try {
      aspect.wrap(buildJoinPoint());

      org.mockito.Mockito.verifyNoInteractions(usageRecorder);
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

      verify(usageRecorder)
          .record(
              org.mockito.ArgumentMatchers.eq("system"),
              org.mockito.ArgumentMatchers.eq("auth.logout"),
              org.mockito.ArgumentMatchers.eq(true),
              org.mockito.ArgumentMatchers.any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("提交后用量投影失败不阻断审计主流程")
  void shouldNotPropagateUsageProjectionFailureAfterAuditCommit() throws Throwable {
    doThrow(new IllegalStateException("usage projection unavailable"))
        .when(usageRecorder)
        .record(anyString(), anyString(), anyBoolean(), any(Instant.class));
    TransactionSynchronizationManager.initSynchronization();
    try {
      aspect.wrap(buildJoinPoint());

      assertThatCode(() ->
              TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit())
          .doesNotThrowAnyException();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("主体与请求上下文均无租户时回退到系统哨兵值")
  void shouldFallbackToSystemSentinelWhenPrincipalAndMdcAreEmpty() throws Throwable {
    SecurityContextHolder.clearContext();

    ProceedingJoinPoint pjp = buildJoinPoint();
    aspect.wrap(pjp);

    ArgumentCaptor<OperationAuditEvent> captor = ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("system");
  }

  @Test
  @DisplayName("主体租户为空时回退到请求上下文租户")
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
  @DisplayName("主体租户存在时以其为准且忽略请求上下文")
  void shouldUsePrincipalTenantWhenPresent() throws Throwable {
    ConsolePrincipal principal =
        new ConsolePrincipal("alice", "tenant-a", Set.of("ROLE_TENANT_USER"));
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
  @DisplayName("全局角色跨租操作时以目标租户参数为准")
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
  @DisplayName("目标租户参数解析为空时继续按主体租户回退")
  void shouldStillFallbackWhenTargetTenantParamResolvesToNull() throws Throwable {
    // targetTenantParam=#tenantId 但入参传 null → 必须继续 principal → MDC → "system" 回退链
    ConsolePrincipal principal =
        new ConsolePrincipal("operator", "tenant-a", Set.of("ROLE_TENANT_ADMIN"));
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
    when(sig.getMethod()).thenReturn(m);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getSignature()).thenReturn((Signature) sig);
    when(pjp.getArgs()).thenReturn(new Object[0]);
    when(pjp.proceed()).thenReturn(null);
    return pjp;
  }

  private ProceedingJoinPoint buildTenantUpdateJoinPoint(String tenantIdArg) throws Throwable {
    Method m = getClass().getMethod("sampleTenantUpdate", String.class);
    MethodSignature sig = mock(MethodSignature.class);
    when(sig.getMethod()).thenReturn(m);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getSignature()).thenReturn((Signature) sig);
    when(pjp.getArgs()).thenReturn(new Object[] {tenantIdArg});
    when(pjp.proceed()).thenReturn(null);
    return pjp;
  }
}
