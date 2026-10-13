package io.github.pinpols.batch.console.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobPartitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.job.support.ConsoleJobQueryMappers;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("作业定义查询服务映射")
class ConsoleJobDefinitionQueryTest {

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobExecutionLogMapper jobExecutionLogMapper;

  @Mock
  private LocalizedErrorRenderer localizedErrorRenderer;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  private ConsoleJobQueryService service;

  @BeforeEach
  void setUp() {
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);
  }

  @Test
  @DisplayName("将精确作业编码和直接前置作业编码映射到同一分页查询")
  void shouldMapExactJobAndDirectPredecessorFilters() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobDefinitionMapper.selectByQuery(any())).thenReturn(List.of());
    when(jobDefinitionMapper.countByQuery(any())).thenReturn(0L);
    JobDefinitionQueryRequest request = new JobDefinitionQueryRequest();
    request.setTenantId("tenant-a");
    request.setExactJobCode("CURRENT_JOB");
    request.setDependsOnJobCode("UPSTREAM_JOB");

    service.jobDefinitions(request);

    ArgumentCaptor<JobDefinitionQuery> captor = ArgumentCaptor.forClass(JobDefinitionQuery.class);
    verify(jobDefinitionMapper).selectByQuery(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().exactJobCode()).isEqualTo("CURRENT_JOB");
    assertThat(captor.getValue().dependsOnJobCode()).isEqualTo("UPSTREAM_JOB");
    assertThat(captor.getValue().enabled()).isTrue();
  }

  @Test
  @DisplayName("请求包含停用作业时移除启用状态过滤")
  void shouldIncludeDisabledJobsOnlyWhenExplicitlyRequested() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobDefinitionMapper.selectByQuery(any())).thenReturn(List.of());
    when(jobDefinitionMapper.countByQuery(any())).thenReturn(0L);
    JobDefinitionQueryRequest request = new JobDefinitionQueryRequest();
    request.setTenantId("tenant-a");
    request.setIncludeDisabled(true);

    service.jobDefinitions(request);

    ArgumentCaptor<JobDefinitionQuery> captor = ArgumentCaptor.forClass(JobDefinitionQuery.class);
    verify(jobDefinitionMapper).selectByQuery(captor.capture());
    assertThat(captor.getValue().enabled()).isNull();
  }
}
