package com.example.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.ConfigChangeLogEntity;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.entity.SecretVersionEntity;
import com.example.batch.console.domain.observability.view.dashboard.ConfigDependentView;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ConfigReleaseMapper;
import com.example.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import com.example.batch.console.mapper.SecretVersionMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.request.config.ConfigReleaseActionRequest;
import com.example.batch.console.web.request.config.ConfigReleaseUpsertRequest;
import com.example.batch.console.domain.ops.web.request.SecretVersionRotateRequest;
import com.example.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleSecretVersionResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultConsoleConfigApplicationServiceTest {

  private static final String TENANT = "t1";

  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConfigReleaseMapper configReleaseMapper;
  @Mock private SecretVersionMapper secretVersionMapper;
  @Mock private ConfigChangeLogMapper configChangeLogMapper;
  @Mock private ConsoleDashboardQueryMapper dashboardQueryMapper;

  @InjectMocks private DefaultConsoleConfigApplicationService service;

  @BeforeEach
  void setUp() {
    when(tenantGuard.resolveTenant(any())).thenReturn(TENANT);
  }

  // ── configReleases ──────────────────────────────────────────────────────

  @Test
  void shouldListConfigReleases_whenQueried() {
    when(configReleaseMapper.selectByQuery(any())).thenReturn(List.of(release(1L, "JOB", "k", 1)));

    ConfigReleaseQueryRequest req = new ConfigReleaseQueryRequest();
    req.setTenantId(TENANT);
    req.setConfigType("JOB");
    req.setConfigKey("k");
    req.setConfigStatus("DRAFT");
    req.setVersionNo(1);

    List<ConsoleConfigReleaseResponse> list = service.configReleases(req);

    assertThat(list).hasSize(1);
    assertThat(list.get(0).id()).isEqualTo(1L);
  }

  // ── createConfigRelease ─────────────────────────────────────────────────

  @Test
  void shouldCreateConfigRelease_andIncrementVersion() {
    when(configReleaseMapper.selectLatestVersionNo(anyMap())).thenReturn(2);
    ConfigReleaseUpsertRequest req = upsertRequest();

    Long versionNo = service.createConfigRelease(req);

    assertThat(versionNo).isEqualTo(3L);
    verify(configReleaseMapper).insertConfigRelease(anyMap());
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  @Test
  void shouldCreateConfigRelease_withVersionOne_whenNoPrior() {
    when(configReleaseMapper.selectLatestVersionNo(anyMap())).thenReturn(null);
    ConfigReleaseUpsertRequest req = upsertRequest();

    Long versionNo = service.createConfigRelease(req);

    assertThat(versionNo).isEqualTo(1L);
  }

  @Test
  void shouldThrowBizException_whenConfigPayloadJsonIsLiteralNull() {
    ConfigReleaseUpsertRequest req = upsertRequest();
    req.setConfigPayloadJson("null");

    assertThatThrownBy(() -> service.createConfigRelease(req))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  void shouldThrowBizException_whenConfigPayloadJsonIsMalformed() {
    ConfigReleaseUpsertRequest req = upsertRequest();
    req.setConfigPayloadJson("{not json");

    assertThatThrownBy(() -> service.createConfigRelease(req)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowBizException_whenEffectiveFromAtNotIso() {
    ConfigReleaseUpsertRequest req = upsertRequest();
    req.setEffectiveFromAt("not-a-date");

    assertThatThrownBy(() -> service.createConfigRelease(req)).isInstanceOf(BizException.class);
  }

  // ── publish / gray / rollback ──────────────────────────────────────────

  @Test
  void shouldPublishConfigRelease_andSetPublishedAt() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));

    ConfigReleaseActionRequest req = actionRequest();
    String status = service.publishConfigRelease(10L, req);

    assertThat(status).isEqualTo(ConfigLifecycleStatus.PUBLISHED.code());
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(configReleaseMapper).updateConfigReleaseStatus(captor.capture());
    assertThat(captor.getValue().get("publishedAt")).isNotNull();
    assertThat(captor.getValue().get("rolledBackAt")).isNull();
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  @Test
  void shouldGrayConfigRelease_andUpdateScope() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));

    ConfigReleaseActionRequest req = actionRequest();
    req.setGrayScopeJson("{\"percent\":10}");

    String status = service.grayConfigRelease(10L, req);

    assertThat(status).isEqualTo(ConfigLifecycleStatus.GRAY.code());
    // grayScope updated twice — once in grayConfigRelease, once inside changeReleaseStatus
    verify(configReleaseMapper, org.mockito.Mockito.times(2)).updateGrayScope(anyMap());
    verify(configReleaseMapper).updateConfigReleaseStatus(anyMap());
  }

  @Test
  void shouldGrayConfigRelease_skipScopeUpdateInner_whenScopeBlank() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));

    ConfigReleaseActionRequest req = actionRequest();
    req.setGrayScopeJson(null);

    String status = service.grayConfigRelease(10L, req);

    assertThat(status).isEqualTo(ConfigLifecycleStatus.GRAY.code());
    // outer updateGrayScope still called once (with null), inner branch skipped
    verify(configReleaseMapper, org.mockito.Mockito.times(1)).updateGrayScope(anyMap());
  }

  @Test
  void shouldThrow_whenLoadReleaseNotFound() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(null);

    ConfigReleaseActionRequest req = actionRequest();
    assertThatThrownBy(() -> service.publishConfigRelease(99L, req))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldRollbackConfigRelease_andSetRolledBackAt() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));

    ConfigReleaseActionRequest req = actionRequest();
    String status = service.rollbackConfigRelease(10L, req);

    assertThat(status).isEqualTo(ConfigLifecycleStatus.ROLLED_BACK.code());
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(configReleaseMapper).updateConfigReleaseStatus(captor.capture());
    assertThat(captor.getValue().get("rolledBackAt")).isNotNull();
    assertThat(captor.getValue().get("publishedAt")).isNull();
  }

  // ── secretVersions / rotate ─────────────────────────────────────────────

  @Test
  void shouldListSecretVersions_whenQueried() {
    when(secretVersionMapper.selectByQuery(any())).thenReturn(List.of(secret(1L, "ref")));

    SecretVersionQueryRequest req = new SecretVersionQueryRequest();
    req.setTenantId(TENANT);
    req.setSecretRef("ref");
    req.setSecretStatus("PUBLISHED");
    req.setCurrentVersion(true);

    List<ConsoleSecretVersionResponse> list = service.secretVersions(req);
    assertThat(list).hasSize(1);
  }

  @Test
  void shouldRotateSecret_andIncrementVersion() {
    when(secretVersionMapper.selectLatestVersionNo(anyMap())).thenReturn(2);

    SecretVersionRotateRequest req = rotateRequest();
    req.setSecretStatus(" published "); // exercises trim+upper branch

    Long versionNo = service.rotateSecretVersion(req);

    assertThat(versionNo).isEqualTo(3L);
    verify(secretVersionMapper).deactivateCurrentVersion(anyMap());
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(secretVersionMapper).insertSecretVersion(captor.capture());
    assertThat(captor.getValue().get("secretStatus")).isEqualTo("PUBLISHED");
    assertThat(captor.getValue().get("currentVersion")).isEqualTo(true);
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  @Test
  void shouldThrowBizException_whenSecretPayloadJsonIsMalformed() {
    SecretVersionRotateRequest req = rotateRequest();
    req.setSecretPayloadJson("{not json");

    assertThatThrownBy(() -> service.rotateSecretVersion(req)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldRotateSecret_withDefaultStatus_whenBlank() {
    when(secretVersionMapper.selectLatestVersionNo(anyMap())).thenReturn(null);

    SecretVersionRotateRequest req = rotateRequest();
    req.setSecretStatus(null);

    Long versionNo = service.rotateSecretVersion(req);

    assertThat(versionNo).isEqualTo(1L);
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(secretVersionMapper).insertSecretVersion(captor.capture());
    assertThat(captor.getValue().get("secretStatus"))
        .isEqualTo(ConfigLifecycleStatus.PUBLISHED.code());
  }

  // ── configChangeLogs ────────────────────────────────────────────────────

  @Test
  void shouldListConfigChangeLogs() {
    ConfigChangeLogEntity entity = new ConfigChangeLogEntity();
    entity.setId(1L);
    entity.setTenantId(TENANT);
    entity.setConfigType("JOB");
    entity.setConfigKey("k");
    entity.setVersionNo(1);
    entity.setChangeAction("CREATE");
    entity.setChangeResult("SUCCESS");
    entity.setOperatorType("API");
    entity.setOperatorId("op");
    entity.setTraceId("tr");
    entity.setChangeSummary("{}");
    entity.setCreatedAt(Instant.now());
    when(configChangeLogMapper.selectByQuery(any())).thenReturn(List.of(entity));

    ConfigChangeLogQueryRequest req = new ConfigChangeLogQueryRequest();
    req.setTenantId(TENANT);
    req.setConfigType("JOB");
    req.setConfigKey("k");
    req.setChangeAction("CREATE");

    List<ConsoleConfigChangeLogResponse> list = service.configChangeLogs(req);
    assertThat(list).hasSize(1);
  }

  // ── detail / dependencies / diff ────────────────────────────────────────

  @Test
  void shouldReturnConfigReleaseDetail() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));
    ConsoleConfigReleaseResponse resp = service.configReleaseDetail(TENANT, 10L);
    assertThat(resp.id()).isEqualTo(10L);
  }

  @Test
  void shouldThrow_whenConfigReleaseDetailNotFound() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(null);
    assertThatThrownBy(() -> service.configReleaseDetail(TENANT, 99L))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldReturnSecretVersionDetail() {
    when(secretVersionMapper.selectById(anyMap())).thenReturn(secret(7L, "ref"));
    ConsoleSecretVersionResponse resp = service.secretVersionDetail(TENANT, 7L);
    assertThat(resp.id()).isEqualTo(7L);
  }

  @Test
  void shouldThrow_whenSecretVersionDetailNotFound() {
    when(secretVersionMapper.selectById(anyMap())).thenReturn(null);
    assertThatThrownBy(() -> service.secretVersionDetail(TENANT, 99L))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldReturnConfigDependencies_forQueueType() {
    when(dashboardQueryMapper.jobsByQueueCode(TENANT, "q1"))
        .thenReturn(List.of(new ConfigDependentView(1L, "JOB-1", "Job One")));

    Map<String, Object> result = service.configDependencies(TENANT, "QUEUE", "q1");

    assertThat(result.get("dependentJobCount")).isEqualTo(1);
    assertThat(result.get("configType")).isEqualTo("QUEUE");
  }

  @Test
  void shouldReturnConfigDependencies_forCalendarType() {
    when(dashboardQueryMapper.jobsByCalendarCode(TENANT, "c1"))
        .thenReturn(List.of(new ConfigDependentView(2L, "JOB-2", null)));

    Map<String, Object> result = service.configDependencies(TENANT, "BUSINESS_CALENDAR", "c1");
    assertThat(result.get("dependentJobCount")).isEqualTo(1);
  }

  @Test
  void shouldReturnConfigDependencies_forWindowType() {
    when(dashboardQueryMapper.jobsByWindowCode(TENANT, "w1")).thenReturn(List.of());
    Map<String, Object> result = service.configDependencies(TENANT, "BATCH_WINDOW", "w1");
    assertThat(result.get("dependentJobCount")).isEqualTo(0);
  }

  @Test
  void shouldReturnConfigDependencies_forWorkerGroupType() {
    when(dashboardQueryMapper.jobsByWorkerGroup(TENANT, "g1"))
        .thenReturn(List.of(new ConfigDependentView(3L, "JOB-3", "Three")));
    Map<String, Object> result = service.configDependencies(TENANT, "WORKER_GROUP", "g1");
    assertThat(result.get("dependentJobCount")).isEqualTo(1);
  }

  @Test
  void shouldReturnEmptyDependencies_forUnknownType() {
    Map<String, Object> result = service.configDependencies(TENANT, "UNKNOWN", "x");
    assertThat(result.get("dependentJobCount")).isEqualTo(0);
  }

  @Test
  void shouldDiffConfigReleases_whenPayloadDiffers() {
    ConfigReleaseEntity a = release(1L, "JOB", "k", 1);
    a.setConfigPayload("{\"a\":1}");
    a.setGrayScope("{\"x\":1}");
    a.setConfigStatus("PUBLISHED");
    ConfigReleaseEntity b = release(2L, "JOB", "k", 2);
    b.setConfigPayload("{\"a\":2}");
    b.setGrayScope("{\"x\":2}");
    b.setConfigStatus("ROLLED_BACK");
    when(configReleaseMapper.selectById(anyMap())).thenReturn(a, b);

    Map<String, Object> result = service.diffConfigReleases(TENANT, 1L, 2L);

    assertThat(result.get("payloadChanged")).isEqualTo(true);
    assertThat(result.get("grayScopeChanged")).isEqualTo(true);
    assertThat(result.get("statusChanged")).isEqualTo(true);
    assertThat(result).containsKeys("payloadA", "payloadB");
  }

  @Test
  void shouldDiffConfigReleases_andTolerateMalformedHistoricalJson() {
    // 历史 DB 数据可能含坏 JSON(validateJson 守卫加入前的写入);
    // diff 必须降级为 null 比较,而不是穿透 IllegalArgumentException 变 500。
    ConfigReleaseEntity a = release(1L, "JOB", "k", 1);
    a.setConfigPayload("{not json");
    a.setGrayScope("{\"x\":1}");
    ConfigReleaseEntity b = release(2L, "JOB", "k", 2);
    b.setConfigPayload("{\"a\":2}");
    b.setGrayScope("[broken");
    when(configReleaseMapper.selectById(anyMap())).thenReturn(a, b);

    Map<String, Object> result = service.diffConfigReleases(TENANT, 1L, 2L);

    assertThat(result.get("payloadChanged")).isEqualTo(true); // null vs {"a":2}
    assertThat(result.get("grayScopeChanged")).isEqualTo(true); // {"x":1} vs null
  }

  @Test
  void shouldDiffConfigReleases_whenPayloadSame() {
    ConfigReleaseEntity a = release(1L, "JOB", "k", 1);
    ConfigReleaseEntity b = release(2L, "JOB", "k", 2);
    when(configReleaseMapper.selectById(anyMap())).thenReturn(a, b);

    Map<String, Object> result = service.diffConfigReleases(TENANT, 1L, 2L);

    assertThat(result.get("payloadChanged")).isEqualTo(false);
    assertThat(result.get("grayScopeChanged")).isEqualTo(false);
    assertThat(result.get("statusChanged")).isEqualTo(false);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static ConfigReleaseEntity release(Long id, String type, String key, Integer version) {
    ConfigReleaseEntity entity = new ConfigReleaseEntity();
    entity.setId(id);
    entity.setTenantId(TENANT);
    entity.setConfigType(type);
    entity.setConfigKey(key);
    entity.setConfigName("name");
    entity.setConfigStatus("DRAFT");
    entity.setVersionNo(version);
    entity.setCreatedBy("op");
    entity.setUpdatedBy("op");
    return entity;
  }

  private static SecretVersionEntity secret(Long id, String ref) {
    SecretVersionEntity entity = new SecretVersionEntity();
    entity.setId(id);
    entity.setTenantId(TENANT);
    entity.setSecretRef(ref);
    entity.setSecretName("name");
    entity.setVersionNo(1);
    entity.setSecretStatus("PUBLISHED");
    entity.setCurrentVersion(true);
    return entity;
  }

  private static ConfigReleaseUpsertRequest upsertRequest() {
    ConfigReleaseUpsertRequest req = new ConfigReleaseUpsertRequest();
    req.setTenantId(TENANT);
    req.setConfigType("JOB");
    req.setConfigKey("k");
    req.setConfigName("name");
    req.setConfigPayloadJson("{\"foo\":1}");
    req.setGrayScopeJson("{\"percent\":10}");
    req.setEffectiveFromAt("2026-01-01T00:00:00Z");
    req.setEffectiveToAt("2026-12-31T23:59:59Z");
    req.setOperatorId("op");
    req.setTraceId("tr");
    req.setReason("init");
    return req;
  }

  private static ConfigReleaseActionRequest actionRequest() {
    ConfigReleaseActionRequest req = new ConfigReleaseActionRequest();
    req.setTenantId(TENANT);
    req.setOperatorId("op");
    req.setTraceId("tr");
    req.setReason("action");
    return req;
  }

  private static SecretVersionRotateRequest rotateRequest() {
    SecretVersionRotateRequest req = new SecretVersionRotateRequest();
    req.setTenantId(TENANT);
    req.setSecretRef("ref");
    req.setSecretName("name");
    req.setSecretPayloadJson("{\"k\":\"v\"}");
    req.setRotationWindowStartAt("2026-01-01T00:00:00Z");
    req.setRotationWindowEndAt("2026-01-02T00:00:00Z");
    req.setEffectiveFromAt("2026-01-01T00:00:00Z");
    req.setEffectiveToAt("2026-12-31T23:59:59Z");
    req.setOperatorId("op");
    req.setTraceId("tr");
    req.setReason("rotate");
    return req;
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }

  // suppress unused import warnings on eq / never if any optimizer prunes them
  @SuppressWarnings("unused")
  private void _refs() {
    eq(0);
    never();
  }
}
