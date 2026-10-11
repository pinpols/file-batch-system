package io.github.pinpols.batch.orchestrator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

@DisplayName("协调器优雅停机状态机:验证初始未排空 + 排空启动与取消 + 重复启动只保留首次原因 + 状态快照字段 + 上下文关闭事件触发排空")
class OrchestratorGracefulShutdownTest {

  private OrchestratorGracefulShutdown shutdown;
  private KafkaListenerEndpointRegistry listenerRegistry;
  private MessageListenerContainer triggerLaunchListener;

  @BeforeEach
  void setUp() {
    listenerRegistry = mock(KafkaListenerEndpointRegistry.class);
    triggerLaunchListener = mock(MessageListenerContainer.class);
    when(listenerRegistry.getListenerContainer(
            OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID))
        .thenReturn(triggerLaunchListener);
    shutdown = new OrchestratorGracefulShutdown(listenerRegistry);
  }

  @Test
  @DisplayName("未收到停机信号时排空标记保持关闭,服务仍可正常对外工作")
  void shouldNotBeDrainingInitially() {
    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  @DisplayName("启动排空后立即标记为排空中,停机流程对外可见")
  void shouldStartDraining() {
    shutdown.startDraining("test");

    assertThat(shutdown.isDraining()).isTrue();
    verify(triggerLaunchListener).pause();
  }

  @Test
  @DisplayName("排空流程可被显式取消,取消后排空标记恢复为未排空")
  void shouldStopDraining() {
    shutdown.startDraining("test");
    shutdown.stopDraining("cancel");

    assertThat(shutdown.isDraining()).isFalse();
    verify(triggerLaunchListener).resume();
  }

  @Test
  @DisplayName("重复启动排空时只保留首次原因,已有排空状态不被后续启动覆盖")
  void shouldNotStartDrainingTwice() {
    shutdown.startDraining("first");
    shutdown.startDraining("second");

    assertThat(shutdown.isDraining()).isTrue();
    OrchestratorGracefulShutdown.DrainStatus status = shutdown.status();
    assertThat(status.reason()).isEqualTo("first");
    verify(triggerLaunchListener).pause();
  }

  @Test
  @DisplayName("状态快照同时给出排空标记,排空起始时刻与原因,三者与启动时传入的内容一致")
  void shouldReportStatusCorrectly() {
    shutdown.startDraining("manual");

    OrchestratorGracefulShutdown.DrainStatus status = shutdown.status();
    assertThat(status.draining()).isTrue();
    assertThat(status.drainingSince()).isNotNull();
    assertThat(status.reason()).isEqualTo("manual");
  }

  @Test
  @DisplayName("应用上下文关闭事件触发排空,并以上下文关闭作为排空原因")
  void shouldDrainOnContextClosed() {
    ContextClosedEvent event = mock(ContextClosedEvent.class);

    shutdown.onApplicationEvent(event);

    assertThat(shutdown.isDraining()).isTrue();
    assertThat(shutdown.status().reason()).isEqualTo("context-closed");
    verify(triggerLaunchListener).pause();
  }

  @Test
  @DisplayName("手工取消排空时恢复 Launch listener,不触碰同工厂下的其他 listener")
  void shouldResumeOnlyTriggerLaunchListener_whenDrainCancelled() {
    shutdown.startDraining("manual-enable");
    shutdown.stopDraining("manual-disable");

    verify(listenerRegistry, times(2))
        .getListenerContainer(OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID);
    verify(triggerLaunchListener).pause();
    verify(triggerLaunchListener).resume();
    verify(listenerRegistry, never()).getListenerContainer("workflowTerminalLineageListener");
  }

  @Test
  @DisplayName("Launch listener 未注册时拒绝手工排空, 不返回虚假的成功状态")
  void shouldRejectDrain_whenLaunchListenerIsMissing() {
    when(listenerRegistry.getListenerContainer(
            OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID))
        .thenReturn(null);

    assertThatThrownBy(() -> shutdown.startDraining("manual-enable"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not registered");
    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  @DisplayName("Launch listener pause 失败时不进入 drain 状态")
  void shouldRejectDrain_whenLaunchListenerCannotPause() {
    doThrow(new IllegalStateException("listener unavailable"))
        .when(triggerLaunchListener)
        .pause();

    assertThatThrownBy(() -> shutdown.startDraining("manual-enable"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("listener unavailable");
    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  @DisplayName("恢复 listener 失败时维持 drain 状态, 不提前重新接受流量")
  void shouldRemainDraining_whenLaunchListenerCannotResume() {
    shutdown.startDraining("manual-enable");
    doThrow(new IllegalStateException("listener unavailable"))
        .when(triggerLaunchListener)
        .resume();

    assertThatThrownBy(() -> shutdown.stopDraining("manual-disable"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("listener unavailable");
    assertThat(shutdown.isDraining()).isTrue();
  }
}
