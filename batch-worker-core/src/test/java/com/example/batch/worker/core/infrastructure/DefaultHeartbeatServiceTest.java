package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerLoadProvider;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 守护心跳路径的关键不变量:
 *
 * <ul>
 *   <li>workerId 空 / 注册不存在 → 静默 skip(不抛异常,不调 renew)
 *   <li>正常路径 → 收集 currentLoad → renew → 写回 cache
 *   <li>WorkerLoadProvider 抛异常 → currentLoad 静默兜底为 0(不影响心跳)
 *   <li>多 LoadProvider → currentLoad 求和
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DefaultHeartbeatServiceTest {

  @Mock private WorkerSelfRegistrationService registrationService;
  @Mock private WorkerRuntimeState runtimeState;
  @Mock private ObjectProvider<WorkerLoadProvider> loadProviders;

  private DefaultHeartbeatService service;

  @BeforeEach
  void setUp() {
    service = new DefaultHeartbeatService(registrationService, runtimeState, loadProviders);
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
    when(runtimeState.get("w-1")).thenReturn(current);
    when(loadProviders.stream()).thenReturn(Stream.of(() -> 3, () -> 5)); // 求和 = 8

    WorkerRegistration renewed = new WorkerRegistration();
    renewed.setWorkerId("w-1");
    renewed.setCurrentLoad(8);
    when(registrationService.renew(any())).thenReturn(renewed);

    service.beat("w-1");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(registrationService, times(1)).renew(captor.capture());
    assertThat(captor.getValue().getCurrentLoad()).as("renew 时应携带刚收集的 currentLoad 求和").isEqualTo(8);

    verify(runtimeState, times(1)).put(renewed);
  }

  @Test
  @DisplayName("WorkerLoadProvider 抛异常 → currentLoad 兜底 0,心跳仍正常进行")
  void beat_loadProviderThrows_fallsBackToZero() {
    WorkerRegistration current = new WorkerRegistration();
    current.setWorkerId("w-2");
    when(runtimeState.get("w-2")).thenReturn(current);
    when(loadProviders.stream())
        .thenReturn(
            Stream.of(
                () -> {
                  throw new RuntimeException("provider boom");
                }));

    WorkerRegistration renewed = new WorkerRegistration();
    renewed.setWorkerId("w-2");
    when(registrationService.renew(any())).thenReturn(renewed);

    service.beat("w-2");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(registrationService, times(1)).renew(captor.capture());
    assertThat(captor.getValue().getCurrentLoad())
        .as("LoadProvider 异常应被静默吞掉,currentLoad 兜底 0")
        .isZero();
  }

  @Test
  @DisplayName("无 LoadProvider → currentLoad = 0,心跳正常")
  void beat_noLoadProviders_currentLoadZero() {
    WorkerRegistration current = new WorkerRegistration();
    current.setWorkerId("w-3");
    when(runtimeState.get("w-3")).thenReturn(current);
    when(loadProviders.stream()).thenReturn(Stream.<WorkerLoadProvider>empty());
    when(registrationService.renew(any())).thenReturn(current);

    service.beat("w-3");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(registrationService).renew(captor.capture());
    assertThat(captor.getValue().getCurrentLoad()).isZero();
  }
}
