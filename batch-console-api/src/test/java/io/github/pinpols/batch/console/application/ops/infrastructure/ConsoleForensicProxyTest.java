package io.github.pinpols.batch.console.application.ops.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.application.ops.command.ForensicExportCommand;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("取证代理：缺省格式的内部请求契约")
class ConsoleForensicProxyTest {
  @Test
  @DisplayName("格式缺省时下游仍收到 BUNDLE 和已解析的租户与操作人")
  void shouldSendBundleWhenFormatIsMissing() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://orchestrator.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    OrchestratorInternalRestClient internal = mock(OrchestratorInternalRestClient.class);
    when(internal.client()).thenReturn(builder.build());
    TenantIdResolver tenantGuard = mock(TenantIdResolver.class);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    DownstreamFallback fallback = mock(DownstreamFallback.class);
    when(fallback.callOrThrow(eq("orchestrator"), eq("forensic-export-request"), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
    DefaultConsoleOrchestratorProxyService service = new DefaultConsoleOrchestratorProxyService(
        internal, tenantGuard, mock(ConsoleRealtimeEventPort.class), fallback, mock(ConsoleQueryCacheService.class));
    server.expect(requestTo("http://orchestrator.test/internal/forensic/export"))
        .andExpect(content().json("""
            {"tenantId":"t1","bizDateFrom":"2026-05-19","bizDateTo":"2026-05-19",
             "jobCodes":null,"exportFormat":"BUNDLE","requestedBy":"admin"}
            """))
        .andRespond(withSuccess("{\"exportId\":\"fx-001\",\"status\":\"READY\"}", MediaType.APPLICATION_JSON));
    LocalDate date = LocalDate.parse("2026-05-19");
    service.requestForensicExport(new ForensicExportCommand("t1", date, date, null, null, "admin"));
    server.verify();
  }
}
