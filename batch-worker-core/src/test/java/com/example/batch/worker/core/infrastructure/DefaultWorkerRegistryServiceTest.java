package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerRegistryClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 DefaultWorkerRegistryService 关键不变量:
 *
 * <ul>
 *   <li>register 远程响应缺时间戳时本地补齐 registeredAt + lastHeartbeatAt
 *   <li>renew 心跳后刷新本地 lastHeartbeatAt
 *   <li>updateStatus null/blank 兜底为 ONLINE
 * </ul>
 */
class DefaultWorkerRegistryServiceTest {

  @Mock private WorkerRegistryClient client;
  @Mock private BatchDateTimeSupport dateTimeSupport;

  private DefaultWorkerRegistryService service;
  private static final OffsetDateTime FIXED_NOW =
      OffsetDateTime.of(2026, 5, 21, 12, 0, 0, 0, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new DefaultWorkerRegistryService(client, dateTimeSupport);
  }

  @Test
  @DisplayName("register: 远程响应无时间戳 → 本地补 registeredAt + lastHeartbeatAt")
  void register_remoteMissingTimestamps_fallsBackToLocalNow() {
    WorkerRegistration input = new WorkerRegistration();
    input.setWorkerId("w-1");
    WorkerRegistration remoteResponse = new WorkerRegistration();
    remoteResponse.setWorkerId("w-1");
    // registeredAt / lastHeartbeatAt 都没设
    when(client.register(any())).thenReturn(remoteResponse);
    when(dateTimeSupport.nowOffsetUtc()).thenReturn(FIXED_NOW);

    WorkerRegistration result = service.register(input);

    assertThat(result.getRegisteredAt()).isEqualTo(FIXED_NOW);
    assertThat(result.getLastHeartbeatAt())
        .as("lastHeartbeatAt 缺失时与 registeredAt 同步")
        .isEqualTo(FIXED_NOW);
  }

  @Test
  @DisplayName("register: 远程已含 registeredAt + lastHeartbeatAt → 不覆盖")
  void register_remoteHasTimestamps_preservesThem() {
    OffsetDateTime remoteTs = FIXED_NOW.minusMinutes(5);
    WorkerRegistration remoteResponse = new WorkerRegistration();
    remoteResponse.setRegisteredAt(remoteTs);
    remoteResponse.setLastHeartbeatAt(remoteTs);
    when(client.register(any())).thenReturn(remoteResponse);

    WorkerRegistration result = service.register(new WorkerRegistration());

    assertThat(result.getRegisteredAt()).isEqualTo(remoteTs);
    assertThat(result.getLastHeartbeatAt()).isEqualTo(remoteTs);
    verify(dateTimeSupport, times(0)).nowOffsetUtc();
  }

  @Test
  @DisplayName("renew: 心跳后用本地 now 刷新 lastHeartbeatAt(无视远程时间戳)")
  void renew_refreshesLastHeartbeatAtToLocalNow() {
    WorkerRegistration input = new WorkerRegistration();
    WorkerRegistration remoteResponse = new WorkerRegistration();
    remoteResponse.setLastHeartbeatAt(FIXED_NOW.minusMinutes(10)); // 远程返过期值
    when(client.heartbeat(any())).thenReturn(remoteResponse);
    when(dateTimeSupport.nowOffsetUtc()).thenReturn(FIXED_NOW);

    WorkerRegistration result = service.renew(input);

    assertThat(result.getLastHeartbeatAt())
        .as("renew 必须用本地 now 覆盖远程返回,防 clock skew 导致心跳判定异常")
        .isEqualTo(FIXED_NOW);
  }

  @Test
  @DisplayName("deactivate: 直接转发,无额外逻辑")
  void deactivate_delegatesToClient() {
    WorkerRegistration input = new WorkerRegistration();
    input.setWorkerId("w-x");

    service.deactivate(input);

    verify(client, times(1)).deactivate(input);
  }

  @Test
  @DisplayName("updateStatus: null status 兜底为 ONLINE,转发到 client")
  void updateStatus_nullStatus_fallsBackToOnline() {
    WorkerRegistration input = new WorkerRegistration();
    when(client.updateStatus(any())).thenReturn(input);

    service.updateStatus(input, null);

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(client).updateStatus(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
  }

  @Test
  @DisplayName("updateStatus: blank status 兜底为 ONLINE")
  void updateStatus_blankStatus_fallsBackToOnline() {
    WorkerRegistration input = new WorkerRegistration();
    when(client.updateStatus(any())).thenReturn(input);

    service.updateStatus(input, "   ");

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(client).updateStatus(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
  }

  @Test
  @DisplayName("updateStatus: 显式 status 透传不变")
  void updateStatus_explicitStatus_passedThrough() {
    WorkerRegistration input = new WorkerRegistration();
    when(client.updateStatus(any())).thenReturn(input);

    service.updateStatus(input, WorkerRegistryStatus.DRAINING.code());

    ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
    verify(client).updateStatus(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(WorkerRegistryStatus.DRAINING.code());
  }
}
