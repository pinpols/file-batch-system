package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import io.github.pinpols.batch.worker.core.support.WorkerConsumptionControl;
import io.github.pinpols.batch.worker.core.support.WorkerLoadProvider;
import io.github.pinpols.batch.worker.core.support.WorkerSelfRegistrationService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 守护心跳路径的关键不变量:
 *
 * <ul>
 *   <li>workerId 空 / 注册不存在 → 静默 skip(不抛异常,不调 renew)
 *   <li>正常路径 → 收集 currentLoad → renew → 写回 cache
 *   <li>WorkerLoadProvider 抛异常 → currentLoad 静默回退为 0(不影响心跳)
 *   <li>多 LoadProvider → currentLoad 求和
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("心跳服务: 空标识与未知 Worker 的静默跳过, 负载采集与异常回退")
class DefaultHeartbeatServiceTest {

  @Mock
  private WorkerSelfRegistrationService registrationService;

  @Spy
  private WorkerRuntimeState runtimeState = new WorkerRuntimeState();

  @Mock
  private ObjectProvider<WorkerLoadProvider> loadProviders;

  @Mock
  private ObjectProvider<WorkerConsumptionControl> consumptionControls;

  private DefaultHeartbeatService service;

  @BeforeEach
  void setUp() {
    service = new DefaultHeartbeatService(
        registrationService, runtimeState, loadProviders, consumptionControls);
  }

  @Test
  @DisplayName("workerId 为 null → 静默 skip,不调 renew")
  void beat_nullWorkerId_skipsSilently() {
    service.beat(null);
    verify(registrationService, never()).renew(any());
    verify(runtimeState, never()).put(any());
  }

  @Test
  @DisplayName("workerId 为空串 → 静默 skip")
  void beat_blankWorkerId_skipsSilently() {
    service.beat("   ");
    verify(registrationService, never()).renew(any());
  }

  @Test
  @DisplayName("WorkerRuntimeState 未含该 workerId → 静默 skip,debug 日志,不调 renew")
  void beat_unknownWorker_skipsSilently() {
    when(runtimeState.get("w-unknown")).thenReturn(null);
    service.beat("w-unknown");
    verify(registrationService, never()).renew(any());
    verify(runtimeState, never()).put(any());
  }

  @Test
  @DisplayName("正常路径: 收集 currentLoad → renew → 写回 cache,顺序与字段一致")
  void beat_happyPath_collectsLoadAndRenews() {
    WorkerRegistration current = new WorkerRegistration();
    current.setWorkerId("w-1");
    runtimeState.put(current);
    when(loadProviders.stream()).thenReturn(Stream.of(() -> 3, () -> 5)); // 求和 = 8

    WorkerRegistration renewed = new WorkerRegistration();
    renewed.setWorkerId("w-1");
    renewed.setCurrentLoad(8);
    when(registrationService.renew(any())).thenReturn(renewed);

    service.beat("w-1");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(registrationService, times(1)).renew(captor.capture());
    assertThat(captor.getValue().getCurrentLoad())
        .as("renew 时应携带刚收集的 currentLoad 求和")
        .isEqualTo(8);

    assertThat(runtimeState.get("w-1")).isSameAs(renewed);
  }

  @Test
  @DisplayName("WorkerLoadProvider 抛异常 → currentLoad 回退 0,心跳仍正常进行")
  void beat_loadProviderThrows_fallsBackToZero() {
    WorkerRegistration current = new WorkerRegistration();
    current.setWorkerId("w-2");
    runtimeState.put(current);
    when(loadProviders.stream()).thenReturn(Stream.of(() -> {
      throw new RuntimeException("provider boom");
    }));

    WorkerRegistration renewed = new WorkerRegistration();
    renewed.setWorkerId("w-2");
    when(registrationService.renew(any())).thenReturn(renewed);

    service.beat("w-2");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(registrationService, times(1)).renew(captor.capture());
    assertThat(captor.getValue().getCurrentLoad())
        .as("LoadProvider 异常应被静默捕获并抑制,currentLoad 回退 0")
        .isZero();
  }

  @Test
  @DisplayName("无 LoadProvider → currentLoad = 0,心跳正常")
  void beat_noLoadProviders_currentLoadZero() {
    WorkerRegistration current = new WorkerRegistration();
    current.setWorkerId("w-3");
    runtimeState.put(current);
    when(loadProviders.stream()).thenReturn(Stream.<WorkerLoadProvider>empty());
    when(registrationService.renew(any())).thenReturn(current);

    service.beat("w-3");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(registrationService).renew(captor.capture());
    assertThat(captor.getValue().getCurrentLoad()).isZero();
  }

  @Test
  @DisplayName("平台心跳返回排空状态时通知所有消费控制器停止拉取")
  void beat_drainingDirectivePausesConsumers() {
    WorkerRegistration current = new WorkerRegistration();
    current.setWorkerId("w-drain");
    runtimeState.put(current);
    when(loadProviders.stream()).thenReturn(Stream.empty());

    WorkerRegistration renewed = new WorkerRegistration();
    renewed.setWorkerId("w-drain");
    renewed.setStatus(WorkerRegistryStatus.DRAINING.code());
    when(registrationService.renew(any())).thenReturn(renewed);
    WorkerConsumptionControl control = mock(WorkerConsumptionControl.class);
    when(consumptionControls.orderedStream()).thenReturn(Stream.of(control));

    service.beat("w-drain");

    verify(control).setPlatformDraining(true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("已下线或重新注册后，旧心跳不能恢复缓存、覆盖新注册或下发过期指令")
  void shouldIgnoreLateHeartbeat_whenRegistrationWasRemovedOrReplaced(boolean replaceRegistration)
      throws Exception {
    WorkerRegistration original = new WorkerRegistration();
    original.setWorkerId("worker-race");
    runtimeState.put(original);
    when(loadProviders.stream()).thenReturn(Stream.empty());
    WorkerRegistration renewed = new WorkerRegistration();
    renewed.setWorkerId("worker-race");
    renewed.setStatus(WorkerRegistryStatus.DRAINING.code());
    CountDownLatch requestStarted = new CountDownLatch(1);
    CountDownLatch responseAllowed = new CountDownLatch(1);
    when(registrationService.renew(original)).thenAnswer(invocation -> {
      requestStarted.countDown();
      if (!responseAllowed.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test did not release heartbeat response");
      }
      return renewed;
    });
    WorkerConsumptionControl control = mock(WorkerConsumptionControl.class);
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("control", control);
    DefaultHeartbeatService racingService = new DefaultHeartbeatService(
        registrationService,
        runtimeState,
        loadProviders,
        beans.getBeanProvider(WorkerConsumptionControl.class));
    CompletableFuture<Void> heartbeat =
        CompletableFuture.runAsync(() -> racingService.beat("worker-race"));
    try {
      assertThat(requestStarted.await(3, TimeUnit.SECONDS)).isTrue();
      runtimeState.remove("worker-race");
      WorkerRegistration replacement = new WorkerRegistration();
      replacement.setWorkerId("worker-race");
      if (replaceRegistration) {
        replacement.setCurrentLoad(original.getCurrentLoad());
        assertThat(replacement).isEqualTo(original).isNotSameAs(original);
        runtimeState.put(replacement);
      }
      responseAllowed.countDown();
      heartbeat.get(3, TimeUnit.SECONDS);

      assertThat(runtimeState.get("worker-race"))
          .isSameAs(replaceRegistration ? replacement : null);
      verify(control, never()).setPlatformDraining(anyBoolean());
    } finally {
      responseAllowed.countDown();
      heartbeat.get(3, TimeUnit.SECONDS);
    }
  }
}
