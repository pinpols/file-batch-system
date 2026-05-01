package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * 单元测试：{@link GracefulKafkaShutdown} drain 可观测性指标。
 *
 * <p>覆盖 v6 hardening：drain 完成后必须发出 duration / initial_active_leases / outcome 三个 metric。
 */
class GracefulKafkaShutdownTest {

  private WorkerRuntimeState runtimeState;
  private WorkerSelfRegistrationService registryService;
  private KafkaListenerEndpointRegistry kafkaRegistry;
  private ActiveTaskLeaseRegistry leaseRegistry;
  private SimpleMeterRegistry meterRegistry;
  private GracefulKafkaShutdown shutdown;

  @BeforeEach
  void setUp() throws Exception {
    runtimeState = mock(WorkerRuntimeState.class);
    registryService = mock(WorkerSelfRegistrationService.class);
    kafkaRegistry = mock(KafkaListenerEndpointRegistry.class);
    leaseRegistry = new ActiveTaskLeaseRegistry();
    meterRegistry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
        (ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) mock(ObjectProvider.class);
    lenient().when(provider.getIfAvailable()).thenReturn(meterRegistry);
    lenient().when(runtimeState.snapshot()).thenReturn(List.of());

    shutdown =
        new GracefulKafkaShutdown(
            runtimeState, registryService, kafkaRegistry, leaseRegistry, provider);

    Field f = GracefulKafkaShutdown.class.getDeclaredField("gracefulShutdownTimeoutSeconds");
    f.setAccessible(true);
    f.set(shutdown, 1L); // 短 timeout 加快测试
  }

  @Test
  void shouldRecordSuccessOutcomeWhenNoActiveLeases() {
    shutdown.onApplicationEvent(new ContextClosedEvent(mock(ApplicationContextStub.class)));

    assertThat(meterRegistry.find("batch.worker.drain.duration_seconds").timer()).isNotNull();
    assertThat(
            meterRegistry
                .find("batch.worker.drain.outcome_total")
                .tag("outcome", "success")
                .counter())
        .isNotNull();
    assertThat(
            meterRegistry
                .find("batch.worker.drain.outcome_total")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldRecordTimeoutOutcomeWhenLeasesRemainAfterTimeout() {
    leaseRegistry.register("task-1", "t1", "w1"); // 不会被 remove → 必触发 timeout

    long start = System.currentTimeMillis();
    shutdown.onApplicationEvent(new ContextClosedEvent(mock(ApplicationContextStub.class)));
    long elapsed = System.currentTimeMillis() - start;

    // 至少等待了 timeout（1s），实际 1~3s 可接受
    assertThat(elapsed).isGreaterThanOrEqualTo(900);

    assertThat(
            meterRegistry
                .find("batch.worker.drain.outcome_total")
                .tag("outcome", "timeout")
                .counter()
                .count())
        .isEqualTo(1.0);
    // initialActive=1 → counter 也应该有值
    assertThat(meterRegistry.find("batch.worker.drain.initial_active_leases").counter().count())
        .isEqualTo(1.0);
  }

  /** 占位 stub：ContextClosedEvent 需要一个 ApplicationContext source；测试不实际使用。 */
  private interface ApplicationContextStub extends org.springframework.context.ApplicationContext {}
}
