package io.github.pinpols.batch.orchestrator.infrastructure.timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.TimeoutEnforcerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 单元测试：{@link TaskTimeoutEnforcer} startToClose 超时软取消。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务超时执行器:关闭中跳过扫描,无候选时不取消,对每个超时任务各发起一次取消并累计取消计数")
class TaskTimeoutEnforcerTest {

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private BatchOrchestratorGovernanceProperties governance;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final TimeoutEnforcerProperties timeoutProps = new TimeoutEnforcerProperties();

  private TaskTimeoutEnforcer enforcer;

  @BeforeEach
  void setUp() {
    enforcer = new TaskTimeoutEnforcer(jobTaskMapper, governance, gracefulShutdown, meterRegistry);
  }

  private static JobTaskEntity task(Long id, String tenantId) {
    JobTaskEntity t = new JobTaskEntity();
    t.setId(id);
    t.setTenantId(tenantId);
    t.setTaskTimeoutSeconds(60);
    return t;
  }

  @Test
  @DisplayName("优雅关闭进行中时跳过本轮扫描,不查询任何超时任务候选")
  void shouldSkipScan_whenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    enforcer.enforce();

    verify(jobTaskMapper, never()).selectTaskTimeoutCandidates(anyInt());
  }

  @Test
  @DisplayName("没有超时任务候选时不做任何取消操作")
  void shouldNotRequestCancel_whenNoTimeoutCandidates() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(governance.timeout()).thenReturn(timeoutProps);
    when(jobTaskMapper.selectTaskTimeoutCandidates(anyInt())).thenReturn(List.of());

    enforcer.enforce();

    verify(jobTaskMapper, never()).requestCancel(anyString(), anyLong());
  }

  @Test
  @DisplayName("每个超时任务各发起一次取消请求,取消计数等于超时任务数量")
  void shouldRequestCancelForEachTimedOutTask_whenCandidatesPresent() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(governance.timeout()).thenReturn(timeoutProps);
    when(jobTaskMapper.selectTaskTimeoutCandidates(anyInt()))
        .thenReturn(List.of(task(1L, "t1"), task(2L, "t1")));
    when(jobTaskMapper.requestCancel("t1", 1L)).thenReturn(1);
    when(jobTaskMapper.requestCancel("t1", 2L)).thenReturn(1);

    enforcer.enforce();

    verify(jobTaskMapper).requestCancel("t1", 1L);
    verify(jobTaskMapper).requestCancel("t1", 2L);
    assertThat(meterRegistry.counter("batch.task.timeout.cancel.total").count()).isEqualTo(2.0);
  }
}
