package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * AbstractWorkerLoop 单元测试： - ensureStarted() 是幂等的（仅注册一次） - 注册信息从 WorkerConfiguration 正确填充 -
 * doHeartbeat() 委托给 HeartbeatService - doHeartbeat() 在尚未启动时也是安全的 - shutdown() 委托给
 * WorkerLifecycleManager - shutdown() 在从未启动时也是安全的
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Worker 主循环基类: 注册幂等, 端口选择, 心跳与关闭委托")
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
  @DisplayName("首次调用即完成注册并返回注册信息, 注册入口只被调用一次")
  void ensureStarted_registersWorkerOnFirstCall() {
    WorkerRegistration result = loop.ensureStarted();

    assertThat(result).isNotNull();
    assertThat(result.getWorkerId()).isEqualTo("test-worker-001");
    verify(workerLifecycleManager, times(1)).start(any());
  }

  @Test
  @DisplayName("重复调用注册方法保持幂等, 真正注册只发生一次")
  void ensureStarted_isIdempotent_registersOnlyOnce() {
    loop.ensureStarted();
    loop.ensureStarted();
    loop.ensureStarted();

    verify(workerLifecycleManager, times(1)).start(any());
  }

  @Test
  @DisplayName("注册信息从配置填充: 租户, 类型与分组大写归一, 端口及并发上限")
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
    assertThat(sent.getMaxConcurrent()).isEqualTo(8);
    assertThat(sent.getRegisteredAt()).isNotNull();
    assertThat(sent.getLastHeartbeatAt()).isNotNull();
  }

  @Test
  @DisplayName("实际主服务端口优先于配置端口, 不受独立管理服务端口影响")
  void ensureStarted_usesRuntimeBoundMainPort() {
    loop.setEnvironment(environmentWith("local.server.port", "19099", "server.port", "18083"));

    assertThat(registered().getPort()).isEqualTo(19099);
  }

  @Test
  @DisplayName("仅配置端口或管理端口而主服务未绑定时拒绝注册")
  void ensureStarted_rejectsConfiguredOrManagementPortBeforeBinding() {
    loop.setEnvironment(environmentWith("server.port", "18083", "local.management.port", "19090"));
    assertThatThrownBy(loop::ensureStarted)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("main HTTP server");
    verify(workerLifecycleManager, never()).start(any());
  }

  @Test
  @DisplayName("没有 Spring 环境信息时拒绝注册, 不存在子类端口兜底")
  void ensureStarted_rejectsMissingEnvironment() {
    loop.setEnvironment(null);
    assertThatThrownBy(loop::ensureStarted).isInstanceOf(IllegalStateException.class);
    verify(workerLifecycleManager, never()).start(any());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 65536})
  @DisplayName("实际端口不在 TCP 端口范围内时拒绝注册")
  void ensureStarted_rejectsInvalidBoundPort(int port) {
    loop.setEnvironment(environmentWith("local.server.port", Integer.toString(port)));
    assertThatThrownBy(loop::ensureStarted).isInstanceOf(IllegalStateException.class);
    verify(workerLifecycleManager, never()).start(any());
  }

  @Test
  @DisplayName("随机端口配置只在实际绑定后注册, 管理端口不能覆盖主服务端口")
  void ensureStarted_canRetryAfterMainServerBinds() {
    MockEnvironment environment =
        environmentWith("server.port", "0", "local.management.port", "19090");
    loop.setEnvironment(environment);
    loop.doHeartbeat();
    verify(workerLifecycleManager, never()).start(any());
    verify(heartbeatService, never()).beat(any());

    environment.setProperty("local.server.port", "19099");
    assertThat(registered().getPort()).isEqualTo(19099);
  }

  /** 构造带属性的 MockEnvironment；参数按 key/value 成对传入。 */
  private MockEnvironment environmentWith(String... keyValues) {
    MockEnvironment environment = new MockEnvironment();
    for (int index = 0; index < keyValues.length; index += 2) {
      environment.setProperty(keyValues[index], keyValues[index + 1]);
    }
    return environment;
  }

  /** 触发一次幂等注册并返回实际提交给 WorkerLifecycleManager 的注册信息。 */
  private WorkerRegistration registered() {
    loop.ensureStarted();
    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerLifecycleManager).start(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("存在稳定 Worker 编码时以其作为 Worker 标识")
  void ensureStarted_buildWorkerIdFromWorkerCode_whenPresent() {
    loop.ensureStarted();

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(workerLifecycleManager).start(captor.capture());
    assertThat(captor.getValue().getWorkerId()).isEqualTo("fixed-worker-code");
  }

  @Test
  @DisplayName("稳定实例池编码与运行时实例标识分开, 标识追加规范化后的实例值")
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
  @DisplayName("注册完成后心跳委托给心跳服务并携带 Worker 标识")
  void doHeartbeat_sendsHeartbeatAfterStart() {
    loop.ensureStarted();
    loop.doHeartbeat();

    verify(heartbeatService, times(1)).beat("test-worker-001");
  }

  @Test
  @DisplayName("尚未注册时心跳会先触发注册, 随后正常发送一次")
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
  @DisplayName("心跳服务抛异常时静默容忍, 不向调用方抛出")
  void doHeartbeat_continuesGracefullyWhenFacadeThrows() {
    loop.ensureStarted();
    doThrow(new RuntimeException("network error")).when(heartbeatService).beat(any());

    assertThatCode(() -> loop.doHeartbeat()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("上下文关闭后心跳不再触发注册与发送")
  void doHeartbeat_skipsAfterContextClosed() {
    loop.onContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    loop.doHeartbeat();

    verify(workerLifecycleManager, never()).start(any());
    verify(heartbeatService, never()).beat(any());
  }

  @Test
  @DisplayName("已注册时关闭委托给生命周期管理器并携带 Worker 标识")
  void shutdown_delegatesToFacade_whenStarted() {
    loop.ensureStarted();
    loop.shutdown();

    verify(workerLifecycleManager, times(1)).shutdown("test-worker-001");
  }

  @Test
  @DisplayName("从未注册时关闭为空操作, 不调用生命周期管理器")
  void shutdown_isNoOp_whenNeverStarted() {
    TestWorkerLoop freshLoop =
        new TestWorkerLoop(workerLifecycleManager, heartbeatService, dateTimeSupport);
    freshLoop.shutdown();

    verify(workerLifecycleManager, never()).shutdown(any());
  }

  @Test
  @DisplayName("关闭过程抛异常时不向外传播, 委托调用仍已发生")
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
      setEnvironment(new MockEnvironment().withProperty("local.server.port", "9999"));
    }

    TestWorkerLoop(
        WorkerLifecycleManager lifecycleManager,
        HeartbeatService heartbeatService,
        BatchDateTimeSupport dateTimeSupport,
        WorkerIdentityProperties identityProperties) {
      super(lifecycleManager, heartbeatService, dateTimeSupport, 8, identityProperties);
      setEnvironment(new MockEnvironment().withProperty("local.server.port", "9999"));
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
  }
}
