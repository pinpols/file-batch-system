package io.github.pinpols.batch.orchestrator.infrastructure;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Orchestrator 优雅停机状态。
 *
 * <p>用于两类场景：
 *
 * <ul>
 *   <li>应用关闭时自动进入 drain，拒绝新 launch / 新 dispatch。
 *   <li>运维手工先切 drain，再由网关摘流量。
 * </ul>
 */
@Slf4j
@Component
public class OrchestratorGracefulShutdown
    implements ApplicationListener<ContextClosedEvent>, ApplicationEventPublisherAware {

  private final KafkaListenerEndpointRegistry listenerRegistry;
  private final AtomicBoolean draining = new AtomicBoolean(false);
  private volatile Instant drainingSince;
  private volatile String reason;

  // R3-P1-1：可选依赖,经 ApplicationEventPublisherAware 框架回调注入(非 @Autowired field)。
  // 容器内 Spring 调 setApplicationEventPublisher 注入;单测 new 该类时不调用 → 保持 null,
  // 下方所有用法均已 null 守护。
  private ApplicationEventPublisher eventPublisher;

  /** 对外稳定的 drain 状态契约。 */
  public record DrainStatus(boolean draining, Instant drainingSince, String reason) {}

  public OrchestratorGracefulShutdown(KafkaListenerEndpointRegistry listenerRegistry) {
    this.listenerRegistry = listenerRegistry;
  }

  @Override
  public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
    this.eventPublisher = applicationEventPublisher;
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    try {
      startDraining("context-closed");
    } catch (RuntimeException ex) {
      // 上下文正在关闭时，即使 listener 尚未注册或 pause 失败，也不能阻止 Spring 继续关闭容器。
      log.error(
          "failed to pause trigger launch listener during shutdown; Spring will close it with the context: {}",
          SwallowedExceptionLogger.summary(ex),
          ex);
      beginDrainState("context-closed");
      publishReadiness(ReadinessState.REFUSING_TRAFFIC);
    }
  }

  public synchronized void startDraining(String source) {
    if (draining.get()) {
      return;
    }
    beginDrainState(source);
    try {
      triggerLaunchListener().pause();
    } catch (RuntimeException ex) {
      draining.set(false);
      drainingSince = null;
      reason = "drain-pause-failed";
      throw ex;
    }
    log.info("Orchestrator graceful shutdown initiated: draining=true, source={}", source);
    // 状态先拒绝新 launch，再请求 Kafka pause；已进入 listener 的消息由消费端延迟重投。
    publishReadiness(ReadinessState.REFUSING_TRAFFIC);
  }

  public synchronized void stopDraining(String source) {
    if (!draining.get()) {
      return;
    }
    triggerLaunchListener().resume();
    draining.set(false);
    drainingSince = null;
    reason = source;
    log.info("Orchestrator drain cancelled: source={}", source);
    publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
  }

  private MessageListenerContainer triggerLaunchListener() {
    MessageListenerContainer listener = listenerRegistry.getListenerContainer(
        OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID);
    if (listener == null) {
      throw new IllegalStateException("Trigger launch Kafka listener is not registered");
    }
    return listener;
  }

  private void beginDrainState(String source) {
    drainingSince = BatchDateTimeSupport.utcNow();
    reason = source;
    draining.set(true);
  }

  private void publishReadiness(ReadinessState state) {
    if (eventPublisher == null) {
      return;
    }
    try {
      AvailabilityChangeEvent.publish(eventPublisher, this, state);
    } catch (RuntimeException ex) {
      log.warn(
          "publish readiness state {} failed (non-fatal): {}",
          state,
          SwallowedExceptionLogger.summary(ex));
    }
  }

  public boolean isDraining() {
    return draining.get();
  }

  public DrainStatus status() {
    return new DrainStatus(draining.get(), drainingSince, reason);
  }
}
