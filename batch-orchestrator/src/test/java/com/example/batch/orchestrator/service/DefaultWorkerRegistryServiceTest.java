package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.domain.param.TouchHeartbeatParam;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 worker 注册表关键不变量:
 *
 * <ul>
 *   <li>DRAINING / DECOMMISSIONED 不可被心跳重置回 ONLINE(防止 worker 端心跳悄悄回滚运维 drain)
 *   <li>heartbeat 未注册时自动降级到 register(兜底首次 register 丢失)
 *   <li>register 同 workerCode 走 upsert 不报错(支持 worker 重启)
 *   <li>deactivate 走 updateStatus OFFLINE
 * </ul>
 */
class DefaultWorkerRegistryServiceTest {

  @Mock private WorkerRegistryMapper mapper;

  private DefaultWorkerRegistryService service;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    service = new DefaultWorkerRegistryService(mapper);
    // @Lazy self 字段注入,单元测下用反射手动指向自己 (走非事务路径)
    Field self = DefaultWorkerRegistryService.class.getDeclaredField("self");
    self.setAccessible(true);
    self.set(service, service);
  }

  private WorkerHeartbeatDto dto(String status) {
    return new WorkerHeartbeatDto(
        "ta", "w1", "default", status, "host", "1.2.3.4", "pid", Instant.now(), List.of(), 1);
  }

  private WorkerRegistryEntity entityWithStatus(String status) {
    return new WorkerRegistryEntity(
        100L, "ta", "w1", "default", null, null, status, Instant.now(), 1, 10, null, null);
  }

  // ===== heartbeat: drain 不可逆 =====

  @Test
  @DisplayName("heartbeat: DRAINING 状态收到 ONLINE 心跳 → 保持 DRAINING")
  void heartbeatDoesNotRevertDrainingToOnline() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(
            entityWithStatus(WorkerRegistryStatus.DRAINING.code()),
            entityWithStatus(WorkerRegistryStatus.DRAINING.code()));

    service.heartbeat("w1", dto(WorkerRegistryStatus.ONLINE.code()));

    verify(mapper)
        .touchHeartbeat(
            org.mockito.ArgumentMatchers.argThat(
                (TouchHeartbeatParam p) ->
                    WorkerRegistryStatus.DRAINING.code().equals(p.getNextStatus())));
  }

  @Test
  @DisplayName("heartbeat: DECOMMISSIONED 状态收到 ONLINE 心跳 → 保持 DECOMMISSIONED")
  void heartbeatDoesNotRevertDecommissioned() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(
            entityWithStatus(WorkerRegistryStatus.DECOMMISSIONED.code()),
            entityWithStatus(WorkerRegistryStatus.DECOMMISSIONED.code()));

    service.heartbeat("w1", dto(WorkerRegistryStatus.ONLINE.code()));

    verify(mapper)
        .touchHeartbeat(
            org.mockito.ArgumentMatchers.argThat(
                (TouchHeartbeatParam p) ->
                    WorkerRegistryStatus.DECOMMISSIONED.code().equals(p.getNextStatus())));
  }

  @Test
  @DisplayName("heartbeat: ONLINE 状态收到 ONLINE 心跳 → 保持 ONLINE(正常路径)")
  void heartbeatKeepsOnline() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(
            entityWithStatus(WorkerRegistryStatus.ONLINE.code()),
            entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    service.heartbeat("w1", dto(WorkerRegistryStatus.ONLINE.code()));

    verify(mapper)
        .touchHeartbeat(
            org.mockito.ArgumentMatchers.argThat(
                (TouchHeartbeatParam p) ->
                    WorkerRegistryStatus.ONLINE.code().equals(p.getNextStatus())));
  }

  @Test
  @DisplayName("heartbeat: null DTO → 直接返 null,不读 DB")
  void heartbeatNullDtoReturnsNull() {
    assertThat(service.heartbeat("w1", null)).isNull();
    verify(mapper, never()).selectByTenantAndWorkerCode(anyString(), anyString());
  }

  // ===== heartbeat 降级 register =====

  @Test
  @DisplayName("heartbeat: 未注册 → 自动降级走 register(兜底首次 register 丢失)")
  void heartbeatFallsBackToRegisterWhenNotRegistered() {
    // heartbeat 读 null → 走 register;register 内部也读 null → 走 insert 路径
    // 最后 persist 后重读返回 ONLINE entity
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    service.heartbeat("w1", dto(null));

    // 走 register 路径会执行 insert,不应走 touchHeartbeat
    verify(mapper).insert(any());
    verify(mapper, never()).touchHeartbeat(any());
  }

  // ===== register 幂等 upsert =====

  @Test
  @DisplayName("register: 新 worker → insert + 重读")
  void registerNewWorkerInserts() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    WorkerRegistryEntity result = service.register(dto(null));

    verify(mapper).insert(any());
    verify(mapper, never()).updateById(any());
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("register: 已存在 worker(同 workerCode) → updateById,不抛错(重启重连场景)")
  void registerExistingWorkerUpdates() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(
            entityWithStatus(WorkerRegistryStatus.OFFLINE.code()),
            entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    service.register(dto(null));

    verify(mapper).updateById(any());
    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName("register: 同 workerCode 重连应能从 DRAINING 改回 ONLINE(register 不受心跳保护规则限制)")
  void registerCanChangeDrainingToOnline() {
    // 注意:register 走 resolveIncomingStatus(defaultStatus=ONLINE),不走 resolveHeartbeatStatus
    // 所以 DRAINING 在 register 路径下会被覆盖为 ONLINE
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(
            entityWithStatus(WorkerRegistryStatus.DRAINING.code()),
            entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    service.register(dto(null));

    verify(mapper).updateById(any());
  }

  // ===== updateStatus / deactivate =====

  @Test
  @DisplayName("updateStatus: worker 不存在 → 返 null,不写表")
  void updateStatus_missing_returns_null() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("missing"))).thenReturn(null);

    assertThat(service.updateStatus("ta", "missing", WorkerRegistryStatus.OFFLINE.code())).isNull();
    verify(mapper, never()).insert(any());
    verify(mapper, never()).updateById(any());
  }

  @Test
  @DisplayName("deactivate: 触发 updateStatus(OFFLINE)")
  void deactivateMarksOffline() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(
            entityWithStatus(WorkerRegistryStatus.ONLINE.code()),
            entityWithStatus(WorkerRegistryStatus.OFFLINE.code()));

    service.deactivate("ta", "w1");

    verify(mapper, times(1)).updateById(any());
  }
}
