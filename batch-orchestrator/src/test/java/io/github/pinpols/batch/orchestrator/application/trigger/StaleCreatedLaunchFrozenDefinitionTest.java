package io.github.pinpols.batch.orchestrator.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchValidationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("创建态恢复的冻结定义:复用父运行快照,版本漂移拒绝派发")
class StaleCreatedLaunchFrozenDefinitionTest {

  @Mock
  private JobInstanceMapper instances;

  @Mock
  private TriggerRequestMapper requests;

  @Mock
  private PartitionDispatchService dispatch;

  @Mock
  private LaunchService launch;

  @Mock
  private LaunchValidationService validation;

  @Mock
  private OrchestratorGracefulShutdown shutdown;

  private StaleCreatedLaunchRecoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new StaleCreatedLaunchRecoveryScheduler(
        instances,
        requests,
        dispatch,
        launch,
        validation,
        shutdown,
        new SimpleMeterRegistry(),
        new StaleCreatedLaunchRecoveryProperties());
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(10L);
    instance.setTenantId("ta");
    instance.setJobCode("JOB_A");
    instance.setJobDefinitionId(20L);
    instance.setJobDefinitionVersion(1);
    instance.setTriggerRequestId(30L);
    instance.setTriggerType("EVENT");
    instance.setBizDate(LocalDate.of(2026, 1, 15));
    instance.setParamsSnapshot("{\"effectiveParams\":{}}");
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setId(30L);
    request.setTenantId("ta");
    request.setRequestId("request-a");
    request.setRequestStatus(TriggerRequestStatus.LAUNCHED.code());
    when(instances.selectStaleCreatedLaunchCandidates(any(), anyInt()))
        .thenReturn(List.of(instance));
    when(requests.selectById("ta", 30L)).thenReturn(request);
  }

  @Test
  @DisplayName("恢复向 T2 传递与原实例同版本的已验证执行定义")
  void shouldDispatchFrozenDefinition_whenPersistedInstanceVersionMatches() {
    JobDefinitionView frozen = definition(1);
    when(validation.load(any()))
        .thenReturn(new LaunchValidationService.LaunchLoadResult(null, frozen, null, null));

    scheduler.recover();

    ArgumentCaptor<PartitionDispatchService.DispatchContext> context =
        ArgumentCaptor.forClass(PartitionDispatchService.DispatchContext.class);
    verify(dispatch).dispatch(context.capture());
    assertThat(context.getValue().executionDefinition()).isSameAs(frozen);
    verify(requests).reconcileRecoveredCreatedLaunch("ta", "request-a", 10L);
  }

  @Test
  @DisplayName("解析到当前新版本而非原实例版本时不创建分区或回写已恢复")
  void shouldRefuseDispatch_whenResolvedVersionDiffersFromPersistedInstance() {
    when(validation.load(any()))
        .thenReturn(new LaunchValidationService.LaunchLoadResult(null, definition(2), null, null));

    scheduler.recover();

    verify(dispatch, never()).dispatch(any());
    verify(requests, never()).reconcileRecoveredCreatedLaunch(any(), any(), any());
  }

  private static JobDefinitionView definition(int version) {
    return JobDefinitionView.builder()
        .id(20L)
        .tenantId("ta")
        .jobCode("JOB_A")
        .version(version)
        .build();
  }
}
