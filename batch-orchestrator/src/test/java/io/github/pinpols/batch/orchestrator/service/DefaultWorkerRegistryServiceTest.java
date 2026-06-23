package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.dto.WorkerTaskTypeDescriptorDto;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.CustomTaskTypeUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.param.TouchHeartbeatParam;
import io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 守护 worker 注册表关键不变量:
 *
 * <ul>
 *   <li>DRAINING / DECOMMISSIONED 不可被心跳重置回 ONLINE(防止 worker 端心跳悄悄回滚运维 drain)
 *   <li>heartbeat 未注册时自动降级到 register(回退首次 register 丢失)
 *   <li>register 同 workerCode 走 upsert 不报错(支持 worker 重启)
 *   <li>deactivate 走 updateStatus OFFLINE
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DefaultWorkerRegistryServiceTest {

  @Mock private WorkerRegistryMapper mapper;
  @Mock private CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper;
  private final io.github.pinpols.batch.orchestrator.infrastructure.progress
          .PipelineStageProgressCache
      progressCache =
          new io.github.pinpols.batch.orchestrator.infrastructure.progress
              .PipelineStageProgressCache();

  private DefaultWorkerRegistryService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new DefaultWorkerRegistryService(mapper, customTaskTypeRegistryMapper, progressCache);
    // @Lazy self 字段注入,单元测下用反射手动指向自己 (走非事务路径)
    Field self = DefaultWorkerRegistryService.class.getDeclaredField("self");
    self.setAccessible(true);
    self.set(service, service);
  }

  private WorkerHeartbeatDto dto(String status) {
    return dto(status, null);
  }

  private WorkerHeartbeatDto dto(String status, String protocolVersion) {
    return new WorkerHeartbeatDto(
        "ta",
        "w1",
        "default",
        status,
        "host",
        "1.2.3.4",
        "pid",
        "build-1",
        "sdk-1",
        Instant.now(),
        List.of(),
        1,
        null,
        null,
        null,
        protocolVersion);
  }

  private WorkerHeartbeatDto dtoWithTaskTypes(List<WorkerTaskTypeDescriptorDto> taskTypes) {
    return new WorkerHeartbeatDto(
        "ta",
        "w1",
        "sdk-self-hosted",
        WorkerRegistryStatus.ONLINE.code(),
        "host",
        "1.2.3.4",
        "pid",
        "build-1",
        "sdk-1",
        Instant.now(),
        List.of(),
        1,
        taskTypes,
        null,
        null,
        null);
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
  @DisplayName("heartbeat: 未注册 → 自动降级走 register(回退首次 register 丢失)")
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
  @DisplayName("register: 支持的 protocolVersion(v2)→ 正常注册")
  void registerSupportedProtocolVersionAccepted() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    WorkerRegistryEntity result = service.register(dto(WorkerRegistryStatus.ONLINE.code(), "v2"));

    verify(mapper).insert(any());
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("register: 缺 protocolVersion(老 SDK / 非 SDK worker)→ legacy 放行")
  void registerAbsentProtocolVersionAccepted() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    WorkerRegistryEntity result = service.register(dto(WorkerRegistryStatus.ONLINE.code(), null));

    verify(mapper).insert(any());
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("register: 不支持的 protocolVersion(v3)→ 拒绝(VALIDATION_ERROR),不写入数据库")
  void registerUnsupportedProtocolVersionRejected() {
    assertThatThrownBy(() -> service.register(dto(WorkerRegistryStatus.ONLINE.code(), "v3")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unsupported_protocol_version");

    verify(mapper, never()).selectByTenantAndWorkerCode(anyString(), anyString());
    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName("register: 无法解析的 protocolVersion(garbage)→ 拒绝,不写入数据库")
  void registerUnparseableProtocolVersionRejected() {
    assertThatThrownBy(() -> service.register(dto(WorkerRegistryStatus.ONLINE.code(), "abc")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unsupported_protocol_version");

    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName(
      "register: 上报非枚举状态(自托管 SDK 恒发 RUNNING)→ 写入数据库归一为 ONLINE,不违反 ck_worker_registry_status")
  void registerNonEnumStatusNormalizedToOnline() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));
    ArgumentCaptor<WorkerRegistryEntity> captor =
        ArgumentCaptor.forClass(WorkerRegistryEntity.class);

    service.register(dto("RUNNING"));

    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
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

  // ===== register: 自定义 taskType descriptor upsert (SDK Phase 3 M3.1) =====

  @Test
  @DisplayName(
      "register: 上报 taskTypes[] → 每个 descriptor upsert 到 custom_task_type_registry(code 权威)")
  void registerUpsertsDeclaredTaskTypes() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));
    WorkerTaskTypeDescriptorDto descriptor =
        new WorkerTaskTypeDescriptorDto(
            "tenant_ta_import", "导入", "v1", Map.of("batchSize", 500), null, null);

    service.register(dtoWithTaskTypes(List.of(descriptor)));

    verify(customTaskTypeRegistryMapper)
        .upsertDeclared(
            org.mockito.ArgumentMatchers.argThat(
                (CustomTaskTypeUpsertParam p) ->
                    "tenant_ta_import".equals(p.getTaskTypeCode())
                        && "导入".equals(p.getDisplayName())
                        && "v1".equals(p.getDescriptorVersion())
                        && "w1".equals(p.getDeclaredByWorkerCode())
                        && p.getDescriptor() != null
                        && p.getDescriptor().contains("batchSize")));
  }

  @Test
  @DisplayName("register: taskTypes 为 null(file-pipeline worker) → 不触发 upsert")
  void registerWithoutTaskTypesSkipsUpsert() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));

    service.register(dto(null));

    verify(customTaskTypeRegistryMapper, never()).upsertDeclared(any());
  }

  @Test
  @DisplayName("register: descriptor.code 空白 → 跳过该条 upsert(不落脏 code)")
  void registerSkipsBlankCodeDescriptor() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));
    WorkerTaskTypeDescriptorDto blank =
        new WorkerTaskTypeDescriptorDto(" ", "x", "v1", null, null, null);

    service.register(dtoWithTaskTypes(List.of(blank)));

    verify(customTaskTypeRegistryMapper, never()).upsertDeclared(any());
  }

  @Test
  @DisplayName(
      "register: descriptor.defaults 含 secret → Lane C SensitiveDataValidator 抛"
          + " BizException,不写入数据库")
  void registerRejectsDescriptorWithCredential_LaneC() {
    when(mapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(null, entityWithStatus(WorkerRegistryStatus.ONLINE.code()));
    WorkerTaskTypeDescriptorDto leaky =
        new WorkerTaskTypeDescriptorDto(
            "tenant_ta_leak", "leak", "v1", Map.of("apiKey", "leaked-AKIA-token"), null, null);

    assertThatThrownBy(() -> service.register(dtoWithTaskTypes(List.of(leaky))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.security.sensitive_in_payload");

    verify(customTaskTypeRegistryMapper, never()).upsertDeclared(any());
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
