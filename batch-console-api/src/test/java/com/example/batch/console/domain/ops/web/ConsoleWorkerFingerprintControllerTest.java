package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.dto.WorkerCompatibility;
import com.example.batch.console.domain.ops.dto.WorkerCompatibility.ReasonCode;
import com.example.batch.console.domain.ops.dto.WorkerCompatibility.Status;
import com.example.batch.console.domain.ops.service.ConsoleWorkerFingerprintQueryService;
import com.example.batch.console.domain.ops.web.response.WorkerFingerprintResponse;
import com.example.batch.console.domain.ops.web.response.WorkerFingerprintSummaryResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** SDK Phase 5 / SDK-P5-3(console Lane D):验证 ConsoleWorkerFingerprintController 两端点返回包装。 */
@ExtendWith(MockitoExtension.class)
class ConsoleWorkerFingerprintControllerTest {

  @Mock private ConsoleWorkerFingerprintQueryService queryService;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleWorkerFingerprintController controller;

  @Test
  void listReturnsResponsesFromQueryService() {
    WorkerFingerprintResponse row =
        new WorkerFingerprintResponse(
            1L,
            "tx",
            "w-1",
            "build-2026.06.02-a",
            "pid-42",
            "1.4.0",
            "ONLINE",
            Instant.parse("2026-06-02T10:00:00Z"),
            new WorkerCompatibility(Status.OK, ReasonCode.COMPATIBLE, "1.4.0", "v1"));
    when(queryService.list("tx")).thenReturn(List.of(row));
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
              assertThat(r.compatibility().status()).isEqualTo(Status.OK);
              assertThat(r.compatibility().reasonCode()).isEqualTo(ReasonCode.COMPATIBLE);
            });
  }

  @Test
  void summaryReturnsResponsesFromQueryService() {
    when(queryService.summary("tx"))
        .thenReturn(List.of(new WorkerFingerprintSummaryResponse("build-A", "1.4.0", 5L)));
    when(responseFactory.success(ArgumentMatchers.<List<WorkerFingerprintSummaryResponse>>any()))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    List<WorkerFingerprintSummaryResponse> data = controller.summary("tx").data();

    assertThat(data)
        .singleElement()
        .extracting(WorkerFingerprintSummaryResponse::count)
        .isEqualTo(5L);
  }
}
