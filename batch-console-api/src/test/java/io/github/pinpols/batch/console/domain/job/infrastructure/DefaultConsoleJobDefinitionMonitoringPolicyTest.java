package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionCopyRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionUpdateRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.job.support.BuiltinTaskTypeGuard;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("作业监控策略维护:默认值、计划时间边界与缓存一致性")
class DefaultConsoleJobDefinitionMonitoringPolicyTest {

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private TenantIdResolver tenantGuard;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  @Mock
  private ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Mock
  private BuiltinTaskTypeGuard builtinTaskTypeGuard;

  @InjectMocks
  private DefaultConsoleJobDefinitionApplicationService service;

  private final JobMonitoringDefaultsProperties defaults = new JobMonitoringDefaultsProperties();

  @BeforeEach
  void setUp() {
    service = new DefaultConsoleJobDefinitionApplicationService(
        jobDefinitionMapper,
        tenantGuard,
        requestMetadataResolver,
        cacheInvalidationService,
        defaults,
        builtinTaskTypeGuard);
  }

  private void stubTenantAndOperator() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "tenant-a", "tester", null, null));
  }

  @Test
  @DisplayName("创建 Cron 作业时应用监控默认值并原子保存策略")
  void shouldApplyMonitoringDefaults_whenCreatingCronJob() {
    stubTenantAndOperator();
    defaults.setSoftRuntimeSeconds(1200);
    defaults.setStartGraceSeconds(300);
    JobDefinitionCreateRequest request = new JobDefinitionCreateRequest();
    request.setTenantId("tenant-a");
    request.setJobCode("JOB_DAILY");
    request.setJobName("daily job");
    request.setJobType("IMPORT");
    request.setScheduleType("CRON");
    request.setScheduleExpr("0 0 2 * * *");
    request.setCompletionDeadlineEnabled(true);
    request.setCompletionDeadlineLocalTime(LocalTime.of(4, 0));

    AtomicReference<JobDefinitionEntity> inserted = new AtomicReference<>();
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_DAILY")).thenReturn(null);
    doAnswer(invocation -> {
          JobDefinitionEntity entity = invocation.getArgument(0);
          entity.setId(41L);
          inserted.set(entity);
          return 1;
        })
        .when(jobDefinitionMapper)
        .insert(any(JobDefinitionEntity.class));
    when(jobDefinitionMapper.selectById("tenant-a", 41L)).thenAnswer(invocation -> inserted.get());

    var response = service.create(request);

    assertThat(response.softRuntimeSeconds()).isEqualTo(1200);
    assertThat(response.startGraceSeconds()).isEqualTo(300);
    assertThat(response.completionDeadlineLocalTime()).isEqualTo(LocalTime.of(4, 0));
    ArgumentCaptor<JobMonitoringPolicyUpsertParam> policyCaptor =
        ArgumentCaptor.forClass(JobMonitoringPolicyUpsertParam.class);
    verify(jobDefinitionMapper).upsertJobMonitoringPolicy(policyCaptor.capture());
    assertThat(policyCaptor.getValue().getJobDefinitionId()).isEqualTo(41L);
    assertThat(policyCaptor.getValue().getSoftRuntimeSeconds()).isEqualTo(1200);
    assertThat(policyCaptor.getValue().getStartGraceSeconds()).isEqualTo(300);
    assertThat(policyCaptor.getValue().getCompletionDeadlineLocalTime())
        .isEqualTo(LocalTime.of(4, 0));
    verify(cacheInvalidationService).evictJobDefinition("tenant-a", "JOB_DAILY");
  }

  @Test
  @DisplayName("更新依赖作业时保留未提交阈值并显式关闭完成截止时间")
  void shouldPreserveDependencyPolicy_whenUpdatingJob() {
    stubTenantAndOperator();
    JobDefinitionEntity existing = new JobDefinitionEntity();
    existing.setId(51L);
    existing.setTenantId("tenant-a");
    existing.setJobCode("JOB_CHILD");
    existing.setJobName("child job");
    existing.setJobType("PROCESS");
    existing.setScheduleType("FIXED_RATE");
    existing.setDependsOnJobCode("JOB_PARENT");
    existing.setSoftRuntimeSeconds(1800);
    existing.setSoftRuntimeSeverity("ERROR");
    existing.setStartGraceSeconds(240);
    existing.setStartGraceSeverity("WARN");
    existing.setCompletionDeadlineLocalTime(LocalTime.of(5, 0));
    existing.setCompletionDeadlineDayOffset(1);
    existing.setCompletionDeadlineSeverity("CRITICAL");
    existing.setDependencyCompletionWindowSeconds(900);
    existing.setEnabled(true);

    JobDefinitionUpdateRequest request = new JobDefinitionUpdateRequest();
    request.setTenantId("tenant-a");
    request.setCompletionDeadlineEnabled(false);
    when(jobDefinitionMapper.selectById("tenant-a", 51L)).thenReturn(existing);

    service.update(51L, request);

    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> updateCaptor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(updateCaptor.capture());
    assertThat(updateCaptor.getValue().getSoftRuntimeSeconds()).isEqualTo(1800);
    assertThat(updateCaptor.getValue().getStartGraceSeconds()).isEqualTo(240);
    assertThat(updateCaptor.getValue().getCompletionDeadlineLocalTime()).isNull();
    assertThat(updateCaptor.getValue().getCompletionDeadlineDayOffset()).isZero();
    assertThat(updateCaptor.getValue().getDependencyCompletionWindowSeconds()).isEqualTo(900);

    ArgumentCaptor<JobMonitoringPolicyUpsertParam> policyCaptor =
        ArgumentCaptor.forClass(JobMonitoringPolicyUpsertParam.class);
    verify(jobDefinitionMapper).upsertJobMonitoringPolicy(policyCaptor.capture());
    assertThat(policyCaptor.getValue().getCompletionDeadlineLocalTime()).isNull();
    assertThat(policyCaptor.getValue().getDependencyCompletionWindowSeconds()).isEqualTo(900);
    verify(cacheInvalidationService).evictJobDefinition("tenant-a", "JOB_CHILD");
  }

  @Test
  @DisplayName("普通手动作业不能配置计划启动宽限")
  void shouldRejectStartGrace_whenJobHasNoScheduleOrDependency() {
    JobDefinitionCreateRequest request = new JobDefinitionCreateRequest();
    request.setTenantId("tenant-a");
    request.setJobCode("JOB_MANUAL");
    request.setJobName("manual job");
    request.setJobType("PROCESS");
    request.setScheduleType("MANUAL");
    request.setStartGraceSeconds(60);

    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("复制作业时同步复制监控策略并使新作业缓存失效")
  void shouldCopyMonitoringPolicy_whenCopyingJob() {
    stubTenantAndOperator();
    JobDefinitionEntity copied = copyEntity("JOB_COPY");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_COPY")).thenReturn(null, copied);
    when(jobDefinitionMapper.selectById("tenant-a", 61L)).thenReturn(copied);

    var response = service.copy(60L, "tenant-a", "JOB_COPY");

    assertThat(response.jobCode()).isEqualTo("JOB_COPY");
    verify(jobDefinitionMapper).copyJobMonitoringPolicy("tenant-a", 60L, 61L, "tester");
    verify(cacheInvalidationService).evictJobDefinition("tenant-a", "JOB_COPY");
  }

  @Test
  @DisplayName("复制并覆盖作业时保留策略字段并应用基础定义覆盖")
  void shouldPreserveMonitoringPolicy_whenCopyingWithOverrides() {
    stubTenantAndOperator();
    JobDefinitionEntity copied = copyEntity("JOB_COPY_OVERRIDE");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_COPY_OVERRIDE"))
        .thenReturn(null, copied);
    when(jobDefinitionMapper.selectById("tenant-a", 61L)).thenReturn(copied);
    JobDefinitionCopyRequest request = new JobDefinitionCopyRequest();
    request.setTenantId("tenant-a");
    request.setNewJobCode("JOB_COPY_OVERRIDE");
    request.setJobName("overridden name");
    request.setQueueCode("new-queue");
    request.setWorkerGroup("new-group");

    var response = service.copyWithOverrides(60L, request);

    assertThat(response.jobCode()).isEqualTo("JOB_COPY_OVERRIDE");
    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> updateCaptor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(updateCaptor.capture());
    assertThat(updateCaptor.getValue().getJobName()).isEqualTo("overridden name");
    assertThat(updateCaptor.getValue().getQueueCode()).isEqualTo("new_queue");
    verify(jobDefinitionMapper).copyJobMonitoringPolicy("tenant-a", 60L, 61L, "tester");
    verify(cacheInvalidationService).evictJobDefinition("tenant-a", "JOB_COPY_OVERRIDE");
  }

  private static JobDefinitionEntity copyEntity(String jobCode) {
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setId(61L);
    entity.setTenantId("tenant-a");
    entity.setJobCode(jobCode);
    entity.setJobName("copied job");
    entity.setJobType("PROCESS");
    entity.setScheduleType("MANUAL");
    entity.setRetryPolicy("NONE");
    entity.setShardStrategy("NONE");
    entity.setExecutionMode("FULL");
    entity.setEnabled(true);
    entity.setSoftRuntimeSeconds(3600);
    entity.setSoftRuntimeSeverity("ERROR");
    entity.setStartGraceSeconds(0);
    entity.setStartGraceSeverity("WARN");
    entity.setCompletionDeadlineDayOffset(0);
    entity.setCompletionDeadlineSeverity("WARN");
    entity.setDependencyCompletionWindowSeconds(0);
    return entity;
  }
}
