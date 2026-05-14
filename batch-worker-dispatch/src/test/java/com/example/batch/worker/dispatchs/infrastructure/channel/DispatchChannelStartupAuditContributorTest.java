package com.example.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.WorkerStartupAuditContributor.WorkerStartupAuditResult;
import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DispatchChannelStartupAuditContributorTest {

  @Test
  void auditWarnsWhenUnhealthyChannelsExist() {
    DispatchChannelHealthRepository repository = mock(DispatchChannelHealthRepository.class);
    when(repository.countByHealthStatus("DEGRADED")).thenReturn(2L);
    when(repository.countByHealthStatus("UNHEALTHY")).thenReturn(1L);
    // 已被本轮 probe 验证过（overdue=0），UNHEALTHY 是真实故障
    when(repository.countProbeOverdue(any(Instant.class))).thenReturn(0L);
    DispatchChannelStartupAuditContributor contributor =
        new DispatchChannelStartupAuditContributor(
            repository, new DispatchChannelHealthProperties());

    WorkerStartupAuditResult result = contributor.audit();

    assertThat(result.healthy()).isFalse();
    assertThat(result.details()).containsEntry("unhealthyChannels", 1L);
    assertThat(result.details()).containsEntry("pendingFirstProbe", false);
  }

  @Test
  void auditTreatsStartupOverdueUnhealthyAsPendingFirstProbe() {
    DispatchChannelHealthRepository repository = mock(DispatchChannelHealthRepository.class);
    when(repository.countByHealthStatus("DEGRADED")).thenReturn(0L);
    when(repository.countByHealthStatus("UNHEALTHY")).thenReturn(1L);
    // 启动瞬间 probe scheduler 尚未首跑 → 所有 UNHEALTHY 都是 overdue 残留
    when(repository.countProbeOverdue(any(Instant.class))).thenReturn(1L);
    DispatchChannelStartupAuditContributor contributor =
        new DispatchChannelStartupAuditContributor(
            repository, new DispatchChannelHealthProperties());

    WorkerStartupAuditResult result = contributor.audit();

    assertThat(result.healthy()).isTrue();
    assertThat(result.details()).containsEntry("pendingFirstProbe", true);
  }

  @Test
  void auditDoesNotMarkPendingWhenOverdueExceedsUnhealthy() {
    // 反例：1 个真故障 UNHEALTHY（已被 probe 验证）+ overdue 同样为 1 之外还有
    // 真故障早已 overdue → 经过 mapper SQL 收紧后，countProbeOverdue 只数 UNHEALTHY-overdue。
    // 但万一 mapper 行为又回退（DEGRADED 进入计数），strict == 仍能挡住误吞。
    DispatchChannelHealthRepository repository = mock(DispatchChannelHealthRepository.class);
    when(repository.countByHealthStatus("DEGRADED")).thenReturn(3L);
    when(repository.countByHealthStatus("UNHEALTHY")).thenReturn(1L);
    when(repository.countProbeOverdue(any(Instant.class))).thenReturn(3L);
    DispatchChannelStartupAuditContributor contributor =
        new DispatchChannelStartupAuditContributor(
            repository, new DispatchChannelHealthProperties());

    WorkerStartupAuditResult result = contributor.audit();

    assertThat(result.healthy()).isFalse();
    assertThat(result.details()).containsEntry("pendingFirstProbe", false);
  }
}
