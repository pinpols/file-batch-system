package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.WorkerDrainProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 单元测试：{@link WorkerHeartbeatTimeoutScheduler}。
 *
 * <p>v6 hardening 后 cutoff 由 mybatis xml 内 {@code current_timestamp - interval} 计算， scheduler 只负责将
 * timeoutSeconds + graceSeconds 累加传入；测试覆盖：
 *
 * <ul>
 *   <li>正常流：累加 grace 秒数 → 调用 mapper 一次
 *   <li>draining 模式跳过
 *   <li>自定义 timeout / grace 配置生效
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkerHeartbeatTimeoutSchedulerTest {

  @Mock private WorkerRegistryMapper workerRegistryMapper;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private final WorkerDrainProperties props = new WorkerDrainProperties();

  @InjectMocks private WorkerHeartbeatTimeoutScheduler scheduler;

  @BeforeEach
  void setUp() {
    props.setHeartbeatTimeoutSeconds(90);
    props.setHeartbeatGraceSeconds(30);
  }

  @Test
  void computesEffectiveSecondsFromTimeoutAndGrace() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.markStaleHeartbeatsOffline(anyLong())).thenReturn(0);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    scheduler.markStaleWorkersOffline();

    ArgumentCaptor<Long> secondsCaptor = ArgumentCaptor.forClass(Long.class);
    verify(workerRegistryMapper).markStaleHeartbeatsOffline(secondsCaptor.capture());
    assertThat(secondsCaptor.getValue()).isEqualTo(120L); // 90 + 30
  }

  @Test
  void drainingModeSkipsEntirely() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    scheduler.markStaleWorkersOffline();

    verify(workerRegistryMapper, never()).markStaleHeartbeatsOffline(anyLong());
  }

  @Test
  void customTimeoutAndGraceAreRespected() {
    props.setHeartbeatTimeoutSeconds(300);
    props.setHeartbeatGraceSeconds(60);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.markStaleHeartbeatsOffline(anyLong())).thenReturn(2);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    scheduler.markStaleWorkersOffline();

    ArgumentCaptor<Long> secondsCaptor = ArgumentCaptor.forClass(Long.class);
    verify(workerRegistryMapper).markStaleHeartbeatsOffline(secondsCaptor.capture());
    assertThat(secondsCaptor.getValue()).isEqualTo(360L);
  }

  @Test
  void zeroGraceFallsBackToPureTimeout() {
    props.setHeartbeatGraceSeconds(0);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.markStaleHeartbeatsOffline(anyLong())).thenReturn(0);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    scheduler.markStaleWorkersOffline();

    ArgumentCaptor<Long> secondsCaptor = ArgumentCaptor.forClass(Long.class);
    verify(workerRegistryMapper).markStaleHeartbeatsOffline(secondsCaptor.capture());
    assertThat(secondsCaptor.getValue()).isEqualTo(90L);
  }
}
