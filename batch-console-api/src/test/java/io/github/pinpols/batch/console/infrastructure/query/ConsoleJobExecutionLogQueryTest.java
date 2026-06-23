package io.github.pinpols.batch.console.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.page.CursorCodec;
import io.github.pinpols.batch.console.domain.job.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobPartitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.console.domain.job.query.JobExecutionLogQuery;
import io.github.pinpols.batch.console.domain.job.support.ConsoleJobQueryMappers;
import io.github.pinpols.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleJobExecutionLogQueryTest {

  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private JobExecutionLogMapper jobExecutionLogMapper;
  @Mock private LocalizedErrorRenderer localizedErrorRenderer;
  @Mock private BatchTimezoneProvider timezoneProvider;
  @Mock private JobDefinitionMapper jobDefinitionMapper;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;

  private ConsoleJobQueryService service;

  @BeforeEach
  void setUp() {
    ConsoleJobQueryMappers mappers =
        new ConsoleJobQueryMappers(
            jobDefinitionMapper,
            jobInstanceMapper,
            jobStepInstanceMapper,
            jobPartitionMapper,
            jobExecutionLogMapper);
    service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);
  }

  private JobExecutionLogEntity log(long id) {
    JobExecutionLogEntity e = new JobExecutionLogEntity();
    e.setId(id);
    e.setTenantId("t1");
    e.setJobInstanceId(1001L);
    e.setLogLevel("INFO");
    e.setLogType("BUSINESS");
    e.setMessage("step " + id + " done");
    e.setCreatedAt(Instant.parse("2026-05-30T00:00:00Z"));
    return e;
  }

  @Test
  void offsetMode_queriesCountAndMapsRows() {
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    when(jobExecutionLogMapper.selectByQuery(any())).thenReturn(List.of(log(2), log(1)));
    when(jobExecutionLogMapper.countByQuery(any())).thenReturn(2L);

    JobExecutionLogQueryRequest req = new JobExecutionLogQueryRequest();
    req.setTenantId("t1");
    req.setJobInstanceId(1001L);
    req.setLogLevel("INFO");

    PageResponse<ConsoleJobExecutionLogResponse> resp = service.jobExecutionLogs(req);

    assertThat(resp.total()).isEqualTo(2);
    assertThat(resp.items()).hasSize(2);
    assertThat(resp.items().get(0).message()).isEqualTo("step 2 done");

    ArgumentCaptor<JobExecutionLogQuery> captor =
        ArgumentCaptor.forClass(JobExecutionLogQuery.class);
    verify(jobExecutionLogMapper).selectByQuery(captor.capture());
    assertThat(captor.getValue().jobInstanceId()).isEqualTo(1001L);
    assertThat(captor.getValue().logLevel()).isEqualTo("INFO");
    assertThat(captor.getValue().cursorId()).isNull();
  }

  @Test
  void cursorMode_skipsCountAndReturnsNextCursor() {
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    when(jobExecutionLogMapper.selectByQuery(any())).thenReturn(List.of(log(5), log(4)));

    JobExecutionLogQueryRequest req = new JobExecutionLogQueryRequest();
    req.setTenantId("t1");
    req.setJobInstanceId(1001L);
    req.setPageSize(2);
    req.setCursor(CursorCodec.encode(Map.of("id", 9L)));

    PageResponse<ConsoleJobExecutionLogResponse> resp = service.jobExecutionLogs(req);

    assertThat(resp.nextCursor()).isNotBlank();
    assertThat(resp.hasMore()).isTrue();
    verify(jobExecutionLogMapper, never()).countByQuery(any());

    ArgumentCaptor<JobExecutionLogQuery> captor =
        ArgumentCaptor.forClass(JobExecutionLogQuery.class);
    verify(jobExecutionLogMapper).selectByQuery(captor.capture());
    assertThat(captor.getValue().cursorId()).isEqualTo(9L);
  }

  @Test
  void traceMode_allowsTraceWithoutJobInstanceForSnapshot() {
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    when(jobExecutionLogMapper.selectByQuery(any())).thenReturn(List.of(log(7)));
    when(jobExecutionLogMapper.countByQuery(any())).thenReturn(1L);

    JobExecutionLogQueryRequest req = new JobExecutionLogQueryRequest();
    req.setTenantId("t1");
    req.setTraceId("trace-1");

    PageResponse<ConsoleJobExecutionLogResponse> resp = service.jobExecutionLogs(req);

    assertThat(resp.items()).hasSize(1);

    ArgumentCaptor<JobExecutionLogQuery> captor =
        ArgumentCaptor.forClass(JobExecutionLogQuery.class);
    verify(jobExecutionLogMapper).selectByQuery(captor.capture());
    assertThat(captor.getValue().jobInstanceId()).isNull();
    assertThat(captor.getValue().traceId()).isEqualTo("trace-1");
  }
}
