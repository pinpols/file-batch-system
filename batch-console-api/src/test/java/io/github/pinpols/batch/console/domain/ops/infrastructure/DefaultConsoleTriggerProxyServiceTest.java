package io.github.pinpols.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleTriggerStatusResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("触发器列表租户隔离:全局作用域返回全部, 租户作用域只保留本租户并丢弃无法识别条目")
class DefaultConsoleTriggerProxyServiceTest {

  private static final ConsoleTriggerStatusResponse TRIGGER_A = new ConsoleTriggerStatusResponse(
      "tenant-a", "job-a", null, null, null, null, "NORMAL", null, null);
  private static final ConsoleTriggerStatusResponse TRIGGER_B = new ConsoleTriggerStatusResponse(
      "tenant-b", "job-b", null, null, null, null, "NORMAL", null, null);

  // ── filterByTenant 直测:过滤正确性 + fail-closed ──────────────────────────────

  @Test
  @DisplayName("过滤-全局作用域:租户上下文为空时原样返回全部条目")
  void filterByTenant_globalScopeNull_returnsAll() {
    List<ConsoleTriggerStatusResponse> all = List.of(TRIGGER_A, TRIGGER_B);
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(all, null)).isEqualTo(all);
  }

  @Test
  @DisplayName("过滤-租户作用域:仅保留归属本租户的条目, 其余条目被剔除")
  void filterByTenant_tenantScope_keepsOnlyMatching() {
    List<ConsoleTriggerStatusResponse> all = List.of(TRIGGER_A, TRIGGER_B);
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(all, "tenant-a"))
        .containsExactly(TRIGGER_A);
  }

  @Test
  @DisplayName("过滤-失败关闭:缺租户字段的条目按不属本租户丢弃")
  void filterByTenant_unrecognizedItem_droppedFailClosed() {
    // 缺 tenantId 的条目在租户作用域下按「不属本租户」丢弃。
    List<ConsoleTriggerStatusResponse> data = List.of(
        new ConsoleTriggerStatusResponse(null, "x", null, null, null, null, null, null, null),
        TRIGGER_A);
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(data, "tenant-a"))
        .containsExactly(TRIGGER_A);
  }

  // ── triggerList 端到端(mock 下游):作用域驱动过滤 ─────────────────────────────

  @SuppressWarnings("unchecked")
  private DefaultConsoleTriggerProxyService service(
      List<ConsoleTriggerStatusResponse> downstream, ConsoleTenantGuard guard) {
    TriggerInternalRestClient restClientFactory = mock(TriggerInternalRestClient.class);
    RestClient restClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
    when(restClientFactory.client()).thenReturn(restClient);
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
  @DisplayName("列表-租户角色:下游返回多租户数据时只剩本租户条目")
  void triggerList_tenantRole_filtersToOwnTenant() {
    ConsoleTenantGuard guard = mock(ConsoleTenantGuard.class);
    when(guard.currentTenantScopeOrNull()).thenReturn("tenant-a");

    DefaultConsoleTriggerProxyService svc = service(List.of(TRIGGER_A, TRIGGER_B), guard);

    assertThat(svc.triggerList()).containsExactly(TRIGGER_A);
  }

  @Test
  @DisplayName("列表-全局角色:无租户作用域时保留下游返回的全部条目")
  void triggerList_globalRole_returnsAll() {
    ConsoleTenantGuard guard = mock(ConsoleTenantGuard.class);
    when(guard.currentTenantScopeOrNull()).thenReturn(null);

    DefaultConsoleTriggerProxyService svc = service(List.of(TRIGGER_A, TRIGGER_B), guard);

    assertThat(svc.triggerList()).containsExactly(TRIGGER_A, TRIGGER_B);
  }
}
