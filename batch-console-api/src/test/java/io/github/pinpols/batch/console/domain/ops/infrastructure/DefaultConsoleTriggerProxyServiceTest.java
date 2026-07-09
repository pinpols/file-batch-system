package io.github.pinpols.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * SEC-IDOR(S5):{@link DefaultConsoleTriggerProxyService#triggerList()} 按调用方租户收敛。
 *
 * <p>下游 {@code /api/triggers/management/list} 无 tenant 过滤,console 侧按 {@link
 * ConsoleTenantGuard#currentTenantScopeOrNull()} 结果过滤:全局角色(null)见全部,租户角色只见自身。
 */
class DefaultConsoleTriggerProxyServiceTest {

  private static final Object TRIGGER_A =
      Map.of("tenantId", "tenant-a", "jobCode", "job-a", "status", "NORMAL");
  private static final Object TRIGGER_B =
      Map.of("tenantId", "tenant-b", "jobCode", "job-b", "status", "NORMAL");

  // ── filterByTenant 直测:过滤正确性 + fail-closed ──────────────────────────────

  @Test
  void filterByTenant_globalScopeNull_returnsAll() {
    List<Object> all = List.of(TRIGGER_A, TRIGGER_B);
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(all, null)).isEqualTo(all);
  }

  @Test
  void filterByTenant_tenantScope_keepsOnlyMatching() {
    List<Object> all = List.of(TRIGGER_A, TRIGGER_B);
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(all, "tenant-a"))
        .containsExactly(TRIGGER_A);
  }

  @Test
  void filterByTenant_unrecognizedItem_droppedFailClosed() {
    // 非 Map / 缺 tenantId 的条目在租户作用域下按「不属本租户」丢弃
    List<Object> data = List.of("not-a-map", Map.of("jobCode", "x"), TRIGGER_A);
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(data, "tenant-a"))
        .containsExactly(TRIGGER_A);
  }

  // ── triggerList 端到端(mock 下游):作用域驱动过滤 ─────────────────────────────

  @SuppressWarnings("unchecked")
  private DefaultConsoleTriggerProxyService service(
      List<Object> downstream, ConsoleTenantGuard guard) {
    TriggerInternalRestClient restClientFactory = mock(TriggerInternalRestClient.class);
    RestClient restClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
    when(restClientFactory.build()).thenReturn(restClient);
    when(restClient
            .get()
            .uri(any(String.class))
            .retrieve()
            .body(any(ParameterizedTypeReference.class)))
        .thenReturn(CommonResponse.success(downstream));

    DownstreamFallback fallback = mock(DownstreamFallback.class);
    // callOrFallback 直接跑 primary supplier(不模拟熔断/降级)
    when(fallback.callOrFallback(eq("trigger"), eq("list"), any(), any()))
        .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());

    return new DefaultConsoleTriggerProxyService(restClientFactory, guard, fallback);
  }

  @Test
  void triggerList_tenantRole_filtersToOwnTenant() {
    ConsoleTenantGuard guard = mock(ConsoleTenantGuard.class);
    when(guard.currentTenantScopeOrNull()).thenReturn("tenant-a");

    DefaultConsoleTriggerProxyService svc = service(List.of(TRIGGER_A, TRIGGER_B), guard);

    assertThat(svc.triggerList()).containsExactly(TRIGGER_A);
  }

  @Test
  void triggerList_globalRole_returnsAll() {
    ConsoleTenantGuard guard = mock(ConsoleTenantGuard.class);
    when(guard.currentTenantScopeOrNull()).thenReturn(null);

    DefaultConsoleTriggerProxyService svc = service(List.of(TRIGGER_A, TRIGGER_B), guard);

    assertThat(svc.triggerList()).containsExactly(TRIGGER_A, TRIGGER_B);
  }
}
