package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.entity.WorkerFingerprintRow;
import com.example.batch.console.domain.ops.entity.WorkerFingerprintSummaryRow;
import com.example.batch.console.domain.ops.mapper.WorkerFingerprintMapper;
import com.example.batch.console.domain.ops.web.response.WorkerFingerprintResponse;
import com.example.batch.console.domain.ops.web.response.WorkerFingerprintSummaryResponse;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SDK Phase 5 / SDK-P5-3(console Lane D):验证 ConsoleWorkerFingerprintController 两端点的:
 *
 * <ul>
 *   <li>租户解析委托 {@link ConsoleTenantGuard#resolveTenant};
 *   <li>list 端点 Row → Response 转换,字段完整(含 V163 build_id / sdk_version);
 *   <li>summary 端点聚合行 → Response 转换,count null 安全;
 *   <li>返回包装 {@link CommonResponse}。
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsoleWorkerFingerprintControllerTest {

  @Mock private WorkerFingerprintMapper mapper;
  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleWorkerFingerprintController controller;

  private WorkerFingerprintRow row(String workerCode, String buildId, String sdkVersion) {
    WorkerFingerprintRow r = new WorkerFingerprintRow();
    r.setId(1L);
    r.setTenantId("tx");
    r.setWorkerCode(workerCode);
    r.setBuildId(buildId);
    r.setProcessId("pid-42");
    r.setSdkVersion(sdkVersion);
    r.setStatus("ONLINE");
    r.setHeartbeatAt(Instant.parse("2026-06-02T10:00:00Z"));
    return r;
  }

  @Test
  void listResolvesTenantAndMapsRowsToResponses() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.selectFingerprintsByTenant("tx"))
        .thenReturn(List.of(row("w-1", "build-2026.06.02-a", "1.4.0")));
    // ConsoleResponseFactory.success 在测试里只需保 envelope,内容透传即可。
    when(responseFactory.success(ArgumentMatchers.<List<WorkerFingerprintResponse>>any()))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    CommonResponse<List<WorkerFingerprintResponse>> resp = controller.list("tx");

    assertThat(resp.data())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.workerCode()).isEqualTo("w-1");
              assertThat(r.buildId()).isEqualTo("build-2026.06.02-a");
              assertThat(r.sdkVersion()).isEqualTo("1.4.0");
              assertThat(r.processId()).isEqualTo("pid-42");
              assertThat(r.status()).isEqualTo("ONLINE");
              assertThat(r.heartbeatAt()).isEqualTo(Instant.parse("2026-06-02T10:00:00Z"));
            });
  }

  @Test
  void listHandlesNullableFingerprintColumns() {
    // 非 SDK worker:build_id / sdk_version 为 null(V163 不回填历史行)
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.selectFingerprintsByTenant("tx")).thenReturn(List.of(row("legacy-w", null, null)));
    when(responseFactory.success(ArgumentMatchers.<List<WorkerFingerprintResponse>>any()))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    WorkerFingerprintResponse r = controller.list("tx").data().get(0);

    assertThat(r.buildId()).isNull();
    assertThat(r.sdkVersion()).isNull();
    assertThat(r.workerCode()).isEqualTo("legacy-w");
  }

  @Test
  void summaryAggregatesByBuildAndSdkVersion() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    WorkerFingerprintSummaryRow s1 = new WorkerFingerprintSummaryRow();
    s1.setBuildId("build-A");
    s1.setSdkVersion("1.4.0");
    s1.setCount(5L);
    WorkerFingerprintSummaryRow s2 = new WorkerFingerprintSummaryRow();
    s2.setBuildId("(unknown)");
    s2.setSdkVersion("(unknown)");
    s2.setCount(2L);
    when(mapper.selectFingerprintSummaryByTenant("tx")).thenReturn(List.of(s1, s2));
    when(responseFactory.success(ArgumentMatchers.<List<WorkerFingerprintSummaryResponse>>any()))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    List<WorkerFingerprintSummaryResponse> data = controller.summary("tx").data();

    assertThat(data)
        .extracting(
            WorkerFingerprintSummaryResponse::buildId,
            WorkerFingerprintSummaryResponse::sdkVersion,
            WorkerFingerprintSummaryResponse::count)
        .containsExactly(tuple("build-A", "1.4.0", 5L), tuple("(unknown)", "(unknown)", 2L));
  }

  @Test
  void summaryNullCountFallsBackToZero() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    WorkerFingerprintSummaryRow s = new WorkerFingerprintSummaryRow();
    s.setBuildId("build-A");
    s.setSdkVersion("1.4.0");
    s.setCount(null);
    when(mapper.selectFingerprintSummaryByTenant("tx")).thenReturn(List.of(s));
    when(responseFactory.success(ArgumentMatchers.<List<WorkerFingerprintSummaryResponse>>any()))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    assertThat(controller.summary("tx").data().get(0).count()).isZero();
  }

  @Test
  void resolveTenantIsInvokedWithNullForCurrentTenant() {
    when(tenantGuard.resolveTenant(null)).thenReturn("tx-current");
    when(mapper.selectFingerprintsByTenant("tx-current")).thenReturn(List.of());
    when(responseFactory.success(ArgumentMatchers.<List<WorkerFingerprintResponse>>any()))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    assertThat(controller.list(null).data()).isEmpty();
  }
}
