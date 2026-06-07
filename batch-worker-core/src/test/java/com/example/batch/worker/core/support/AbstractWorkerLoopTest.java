package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerRegistration;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

/**
 * AbstractWorkerLoop 单元测试： - ensureStarted() 是幂等的（仅注册一次） - 注册信息从 WorkerConfiguration 正确填充 -
 * doHeartbeat() 委托给 WorkerRuntimeFacade.heartbeat() - doHeartbeat() 在尚未启动时也是安全的 - shutdown() 委托给
 * WorkerRuntimeFacade.shutdown() - shutdown() 在从未启动时也是安全的
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractWorkerLoopTest {

  @Mock private WorkerRuntimeFacade workerRuntimeFacade;

  private TestWorkerLoop loop;
  private BatchDateTimeSupport dateTimeSupport;

  @BeforeEach
  void setUp() {
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("test-worker-001");
    registration.setTenantId("t1");
    when(workerRuntimeFacade.start(any())).thenReturn(registration);

    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    dateTimeSupport = new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider);
    loop = new TestWorkerLoop(workerRuntimeFacade, dateTimeSupport);
  }

  @Test
  void ensureStarted_registersWorkerOnFirstCall() {
    WorkerRegistration result = loop.ensureStarted();

    assertThat(result).isNotNull();
    assertThat(result.getWorkerId()).isEqualTo("test-worker-001");
    verify(workerRuntimeFacade, times(1)).start(any());
  }

  @Test
  void ensureStarted_isIdempotent_registersOnlyOnce() {
    loop.ensureStarted();
    loop.ensureStarted();
    loop.ensureStarted();

    verify(workerRuntimeFacade, times(1)).start(any());
  }

  @Test
  void ensureStarted_populatesRegistrationFromConfiguration() {
    loop.ensureStarted();

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerRuntimeFacade).start(captor.capture());

    WorkerRegistration sent = captor.getValue();
    assertThat(sent.getTenantId()).isEqualTo("t1");
    assertThat(sent.getWorkerType()).isEqualTo("TEST");
    // AbstractWorkerLoop.ensureStarted 源头归一 workerGroup 为大写（防大小写脏数据）
    assertThat(sent.getWorkerGroup()).isEqualTo("TEST");
    assertThat(sent.getPort()).isEqualTo(9999);
    assertThat(sent.getActive()).isTrue();
    assertThat(sent.getRegisteredAt()).isNotNull();
    assertThat(sent.getLastHeartbeatAt()).isNotNull();
  }

  @Test
  void ensureStarted_buildWorkerIdFromWorkerCode_whenPresent() {
    loop.ensureStarted();

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerRuntimeFacade).start(captor.capture());
    assertThat(captor.getValue().getWorkerId()).isEqualTo("fixed-worker-code");
  }

  @Test
  void doHeartbeat_sendsHeartbeatAfterStart() {
    loop.ensureStarted();
    loop.doHeartbeat();

    verify(workerRuntimeFacade, times(1)).heartbeat("test-worker-001");
  }

  @Test
  void doHeartbeat_doesNotFailBeforeStart() {
    // 创建一个尚未调用 start() 的 loop
    TestWorkerLoop freshLoop = new TestWorkerLoop(workerRuntimeFacade, dateTimeSupport);
    // 对未启动的 loop 调用 doHeartbeat 应内部触发 ensureStarted
    // 行为：ensureStarted() 返回有效注册信息，随后心跳正常执行
    freshLoop.doHeartbeat();

    verify(workerRuntimeFacade, times(1)).start(any());
    verify(workerRuntimeFacade, times(1)).heartbeat("test-worker-001");
  }

  @Test
  void doHeartbeat_continuesGracefullyWhenFacadeThrows() {
    loop.ensureStarted();
    doThrow(new RuntimeException("network error")).when(workerRuntimeFacade).heartbeat(any());

    assertThatCode(() -> loop.doHeartbeat()).doesNotThrowAnyException();
  }

  @Test
  void doHeartbeat_skipsAfterContextClosed() {
    loop.onContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    loop.doHeartbeat();

    verify(workerRuntimeFacade, never()).start(any());
    verify(workerRuntimeFacade, never()).heartbeat(any());
  }

  @Test
  void shutdown_delegatesToFacade_whenStarted() {
    loop.ensureStarted();
    loop.shutdown();

    verify(workerRuntimeFacade, times(1)).shutdown("test-worker-001");
  }

  @Test
  void shutdown_isNoOp_whenNeverStarted() {
    TestWorkerLoop freshLoop = new TestWorkerLoop(workerRuntimeFacade, dateTimeSupport);
    freshLoop.shutdown();

    verify(workerRuntimeFacade, never()).shutdown(any());
  }

  @Test
  void shutdown_doesNotPropagateFacadeFailure() {
    loop.ensureStarted();
    doThrow(new RuntimeException("shutdown failed")).when(workerRuntimeFacade).shutdown(any());

    loop.shutdown();

    verify(workerRuntimeFacade, times(1)).shutdown("test-worker-001");
  }

  // ── 用于测试的最小具体子类 ──────────────────────────────

  private static class TestWorkerLoop extends AbstractWorkerLoop {

    TestWorkerLoop(WorkerRuntimeFacade facade, BatchDateTimeSupport dateTimeSupport) {
      super(facade, dateTimeSupport);
    }

    @Override
    protected WorkerConfiguration workerConfiguration() {
      return new WorkerConfiguration() {
        public String workerCode() {
          return "fixed-worker-code";
        }

        public String workerType() {
          return "TEST";
        }

        public String tenantId() {
          return "t1";
        }

        public Long heartbeatIntervalMillis() {
          return 15000L;
        }

        public String topic() {
          return "test-topic";
        }

        public String consumerGroupId() {
          return "test-group";
        }
      };
    }

    @Override
    protected String workerGroup() {
      return "test";
    }

    @Override
    protected int workerPort() {
      return 9999;
    }
  }
}
