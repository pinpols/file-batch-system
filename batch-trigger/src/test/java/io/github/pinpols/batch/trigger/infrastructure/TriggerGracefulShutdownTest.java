package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.event.ContextClosedEvent;

@DisplayName("Trigger 优雅停机协调器:排水状态流转、Quartz standby/start 与生命周期相位契约")
class TriggerGracefulShutdownTest {

  private Scheduler scheduler;
  private TriggerDrainState drainState;
  private TriggerGracefulShutdown shutdown;

  @BeforeEach
  void setUp() {
    scheduler = mock(Scheduler.class);
    drainState = new TriggerDrainState();
    shutdown = new TriggerGracefulShutdown(scheduler, drainState);
  }

  @Test
  @DisplayName("协调器初始不处于排水状态,启动后调度器可正常接受触发")
  void shouldNotBeDrainingInitially() {
    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  @DisplayName("开始排水后状态置为排水并把 Quartz 调度器切到 standby,停止产生新触发")
  void shouldStartDraining() throws SchedulerException {
    shutdown.startDraining("test");

    assertThat(shutdown.isDraining()).isTrue();
    verify(scheduler).standby();
  }

  @Test
  @DisplayName("取消排水后重启调度器并清除排水状态,恢复对外触发能力")
  void shouldStopDraining() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);

    shutdown.startDraining("test");
    shutdown.stopDraining("cancel");

    assertThat(shutdown.isDraining()).isFalse();
    verify(scheduler).start();
  }

  @Test
  @DisplayName("调度器处于 standby 且未启动时对外状态报告为 STANDBY,并同时反映是否正在排水")
  void shouldReportSchedulerStatus() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);
    when(scheduler.isInStandbyMode()).thenReturn(true);
    when(scheduler.isStarted()).thenReturn(false);

    TriggerGracefulShutdown.TriggerDrainStatus status = shutdown.status();

    assertThat(status.draining()).isFalse();
    assertThat(status.schedulerStatus()).isEqualTo("STANDBY");
  }

  @Test
  @DisplayName("应用上下文关闭事件即触发排水,调度器进入 standby 防止停机期间继续触发")
  void shouldStandbyOnContextClosed() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);
    ContextClosedEvent event = mock(ContextClosedEvent.class);

    shutdown.onApplicationEvent(event);

    assertThat(shutdown.isDraining()).isTrue();
    verify(scheduler).standby();
  }

  @Test
  @DisplayName("Quartz 声明为生命周期最早停止且自动启动,确保先于数据源连接池关闭")
  void quartz_stopsAtEarliestLifecyclePhase() {
    assertThat(shutdown.getPhase()).isEqualTo(BatchLifecyclePhases.FIRST_TO_STOP_RELAY);
    assertThat(shutdown.isAutoStartup()).isTrue();
  }

  @Test
  @DisplayName("重复调用 stop 只执行一次 standby,停机流程幂等不重复操作调度器")
  void lifecycleStop_isIdempotent() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);

    shutdown.stop();
    shutdown.stop();

    verify(scheduler).standby();
  }
}
