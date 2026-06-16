package com.example.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import com.example.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import com.example.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleAiToolsTest {

  private static final String TENANT = "tenant-1";

  @Mock private ConsoleQueryApplicationService queryService;

  private ConsoleAiTools tools() {
    return new ConsoleAiTools(TENANT, queryService, 10);
  }

  private ConsoleJobInstanceResponse instance(Long id, String status, String failureClass) {
    return new ConsoleJobInstanceResponse(
        id,
        TENANT,
        "JOB_A",
        "INST-1",
        null,
        "MANUAL",
        status,
        "B1",
        "op",
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        "trace-1",
        null,
        "boom",
        null,
        null,
        null,
        Instant.now(),
        Instant.now(),
        false,
        failureClass);
  }

  @Test
  void getJobInstanceRendersStatusAndBindsTenant() {
    when(queryService.jobInstance(eq(TENANT), eq(42L)))
        .thenReturn(instance(42L, "FAILED", "DOWNSTREAM_ERROR"));

    String out = tools().getJobInstance(42L);

    assertThat(out)
        .contains("jobCode=JOB_A")
        .contains("status=FAILED")
        .contains("DOWNSTREAM_ERROR");
    // 租户由构造绑定,模型只传了 id —— 查询强制限定当前租户
    verify(queryService).jobInstance(TENANT, 42L);
  }

  @Test
  void getJobInstanceReturnsNotFoundMessage() {
    when(queryService.jobInstance(eq(TENANT), eq(99L))).thenReturn(null);
    assertThat(tools().getJobInstance(99L)).contains("未找到").contains("99");
  }

  @Test
  void getJobExecutionLogsBindsTenantAndInstance() {
    PageResponse<ConsoleJobExecutionLogResponse> page =
        new PageResponse<>(
            1,
            1,
            10,
            List.of(
                new ConsoleJobExecutionLogResponse(
                    1L,
                    TENANT,
                    42L,
                    7L,
                    "ERROR",
                    "EXEC",
                    "trace-1",
                    "NPE at line 10",
                    null,
                    null,
                    Instant.now())));
    when(queryService.jobExecutionLogs(any())).thenReturn(page);

    String out = tools().getJobExecutionLogs(42L);

    assertThat(out).contains("ERROR").contains("NPE at line 10");
    ArgumentCaptor<JobExecutionLogQueryRequest> captor =
        ArgumentCaptor.forClass(JobExecutionLogQueryRequest.class);
    verify(queryService).jobExecutionLogs(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getJobInstanceId()).isEqualTo(42L);
  }

  @Test
  void listRecentFailedFiltersByTenantAndFailedStatus() {
    when(queryService.jobInstances(any()))
        .thenReturn(new PageResponse<>(1, 1, 10, List.of(instance(7L, "FAILED", "TIMEOUT"))));

    String out = tools().listRecentFailedJobInstances();

    assertThat(out).contains("id=7").contains("TIMEOUT");
    ArgumentCaptor<JobInstanceQueryRequest> captor =
        ArgumentCaptor.forClass(JobInstanceQueryRequest.class);
    verify(queryService).jobInstances(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getInstanceStatus()).isEqualTo("FAILED");
  }
}
