package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
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
 * doHeartbeat() 委托给 HeartbeatService - doHeartbeat() 在尚未启动时也是安全的 - shutdown() 委托给
 * WorkerLifecycleManager - shutdown() 在从未启动时也是安全的
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractWorkerLoopTest {

  @Mock
  private WorkerLifecycleManager workerLifecycleManager;

  @Mock
  private HeartbeatService heartbeatService;

  private TestWorkerLoop loop;
  private BatchDateTimeSupport dateTimeSupport;

  @BeforeEach
  void setUp() {
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("test-worker-001");
    registration.setTenantId("t1");
    when(workerLifecycleManager.start(any())).thenReturn(registration);

    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    dateTimeSupport = new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider);
    loop = new TestWorkerLoop(workerLifecycleManager, heartbeatService, dateTimeSupport);
  }

  @Test
  void ensureStarted_registersWorkerOnFirstCall() {
    WorkerRegistration result = loop.ensureStarted();

    assertThat(result).isNotNull();
    assertThat(result.getWorkerId()).isEqualTo("test-worker-001");
    verify(workerLifecycleManager, times(1)).start(any());
  }

  @Test
  void ensureStarted_isIdempotent_registersOnlyOnce() {
    loop.ensureStarted();
    loop.ensureStarted();
    loop.ensureStarted();

    verify(workerLifecycleManager, times(1)).start(any());
  }

  @Test
  void ensureStarted_populatesRegistrationFromConfiguration() {
    loop.ensureStarted();

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerLifecycleManager).start(captor.capture());

    WorkerRegistration sent = captor.getValue();
    assertThat(sent.getTenantId()).isEqualTo("t1");
    assertThat(sent.getWorkerType()).isEqualTo("TEST");
    // AbstractWorkerLoop.ensureStarted 源头归一 workerGroup 为大写（防大小写异常数据）
    assertThat(sent.getWorkerGroup()).isEqualTo("TEST");
    assertThat(sent.getPort()).isEqualTo(9999);
    assertThat(sent.getActive()).isTrue();
    assertThat(sent.getMaxConcurrent()).isEqualTo(8);
    assertThat(sent.getRegisteredAt()).isNotNull();
    assertThat(sent.getLastHeartbeatAt()).isNotNull();
  }

  @Test
  void ensureStarted_buildWorkerIdFromWorkerCode_whenPresent() {
    loop.ensureStarted();

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerLifecycleManager).start(captor.capture());
    assertThat(captor.getValue().getWorkerId()).isEqualTo("fixed-worker-code");
  }

  @Test
  void ensureStarted_separatesStablePoolCodeFromRuntimeInstanceId() {
    WorkerIdentityProperties identity = new WorkerIdentityProperties();
    identity.setInstanceId("pod/uid:01");
    TestWorkerLoop instanceLoop =
        new TestWorkerLoop(workerLifecycleManager, heartbeatService, dateTimeSupport, identity);

    instanceLoop.ensureStarted();

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerLifecycleManager).start(captor.capture());
    assertThat(captor.getValue().getWorkerId()).isEqualTo("fixed-worker-code-pod_uid_01");
    assertThat(captor.getValue().getWorkerCode()).isEqualTo("fixed-worker-code");
  }

  @Test
  void doHeartbeat_sendsHeartbeatAfterStart() {
    loop.ensureStarted();
    loop.doHeartbeat();

    verify(heartbeatService, times(1)).beat("test-worker-001");
  }

  @Test
  void doHeartbeat_doesNotFailBeforeStart() {
    // 创建一个尚未调用 start() 的 loop
    TestWorkerLoop freshLoop =
        new TestWorkerLoop(workerLifecycleManager, heartbeatService, dateTimeSupport);
    // 对未启动的 loop 调用 doHeartbeat 应内部触发 ensureStarted
    // 行为：ensureStarted() 返回有效注册信息，随后心跳正常执行
    freshLoop.doHeartbeat();

    verify(workerLifecycleManager, times(1)).start(any());
    verify(heartbeatService, times(1)).beat("test-worker-001");
  }

  @Test
  void doHeartbeat_continuesGracefullyWhenFacadeThrows() {
    loop.ensureStarted();
    doThrow(new RuntimeException("network error")).when(heartbeatService).beat(any());

    assertThatCode(() -> loop.doHeartbeat()).doesNotThrowAnyException();
  }

  @Test
  void doHeartbeat_skipsAfterContextClosed() {
    loop.onContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    loop.doHeartbeat();

    verify(workerLifecycleManager, never()).start(any());
    verify(heartbeatService, never()).beat(any());
  }

  @Test
  void shutdown_delegatesToFacade_whenStarted() {
    loop.ensureStarted();
    loop.shutdown();

    verify(workerLifecycleManager, times(1)).shutdown("test-worker-001");
  }

  @Test
  void shutdown_isNoOp_whenNeverStarted() {
    TestWorkerLoop freshLoop =
        new TestWorkerLoop(workerLifecycleManager, heartbeatService, dateTimeSupport);
    freshLoop.shutdown();

    verify(workerLifecycleManager, never()).shutdown(any());
  }

  @Test
  void shutdown_doesNotPropagateFacadeFailure() {
    loop.ensureStarted();
    doThrow(new RuntimeException("shutdown failed"))
        .when(workerLifecycleManager)
        .shutdown(any());

    loop.shutdown();

    verify(workerLifecycleManager, times(1)).shutdown("test-worker-001");
  }

  // ── 用于测试的最小具体子类 ──────────────────────────────

  private static class TestWorkerLoop extends AbstractWorkerLoop {

    TestWorkerLoop(
        WorkerLifecycleManager lifecycleManager,
        HeartbeatService heartbeatService,
        BatchDateTimeSupport dateTimeSupport) {
      super(lifecycleManager, heartbeatService, dateTimeSupport, 8);
    }

    TestWorkerLoop(
        WorkerLifecycleManager lifecycleManager,
        HeartbeatService heartbeatService,
        BatchDateTimeSupport dateTimeSupport,
        WorkerIdentityProperties identityProperties) {
      super(lifecycleManager, heartbeatService, dateTimeSupport, 8, identityProperties);
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
