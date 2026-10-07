package io.github.pinpols.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.application.contract.response.ops.ConsoleTriggerStatusResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Trigger 代理真实 JSON 转换验证：固定列表、时间字段与租户失败关闭")
class TriggerProxyJsonContractTest {

  @Test
  @DisplayName("内部响应反序列化为固定 DTO，跨租户与缺租户记录均不透传")
  void shouldDeserializeAndFilterWireRows_whenDownstreamReturnsMultipleTenants() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://trigger.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://trigger.test/api/triggers/management/list"))
        .andRespond(withSuccess("""
            {"code":"SUCCESS","data":[
              {"tenantId":"ta","jobCode":"JOB_A","status":"NORMAL","nextFireTime":"2026-10-07T00:00:00Z"},
              {"tenantId":"tb","jobCode":"JOB_B","status":"NORMAL"},
              {"jobCode":"UNKNOWN","status":"NORMAL"}
            ]}
            """, MediaType.APPLICATION_JSON));
    TriggerInternalRestClient factory = mock(TriggerInternalRestClient.class);
    when(factory.client()).thenReturn(builder.build());
    ConsoleTenantGuard guard = mock(ConsoleTenantGuard.class);
    when(guard.currentTenantScopeOrNull()).thenReturn("ta");
    DownstreamFallback fallback = mock(DownstreamFallback.class);
    when(fallback.callOrFallback(eq("trigger"), eq("list"), any(), any()))
        .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
    List<ConsoleTriggerStatusResponse> rows =
        new DefaultConsoleTriggerProxyService(factory, guard, fallback).triggerList();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).jobCode()).isEqualTo("JOB_A");
    assertThat(rows.get(0).nextFireTime()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
    server.verify();
  }

  @Test
  @DisplayName("空的内部列表不会返回 null，也不会产生错误的租户记录")
  void shouldReturnEmptyList_whenDownstreamRowsAreNull() {
    assertThat(DefaultConsoleTriggerProxyService.filterByTenant(null, "ta")).isEqualTo(List.of());
  }
}
