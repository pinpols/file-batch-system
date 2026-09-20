package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.domain.entity.ConfigChangeLogEntity;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ConfigDependentView;
import io.github.pinpols.batch.console.domain.ops.web.request.SecretVersionRotateRequest;
import io.github.pinpols.batch.console.domain.rbac.entity.SecretVersionEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.SecretVersionMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigChangeLogMapper;
import io.github.pinpols.batch.console.mapper.ConfigReleaseMapper;
import io.github.pinpols.batch.console.shared.view.ConsoleSecretVersionResponse;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.query.ConfigChangeLogQueryRequest;
import io.github.pinpols.batch.console.web.query.ConfigReleaseQueryRequest;
import io.github.pinpols.batch.console.web.query.SecretVersionQueryRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseActionRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseUpsertRequest;
import io.github.pinpols.batch.console.web.response.config.ConfigDependenciesResponse;
import io.github.pinpols.batch.console.web.response.config.ConfigReleaseDiffResponse;
import io.github.pinpols.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import io.github.pinpols.batch.console.web.response.config.ConsoleConfigReleaseResponse;
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

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConfigReleaseMapper configReleaseMapper;

  @Mock
  private SecretVersionMapper secretVersionMapper;

  @Mock
  private ConfigChangeLogMapper configChangeLogMapper;

  @Mock
  private ConsoleDashboardQueryMapper dashboardQueryMapper;

  @Mock
  private ConfigurationGovernanceCatalog governanceCatalog;

  @Mock
  private ConfigReleaseApplyService configReleaseApplyService;

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  @InjectMocks
  private DefaultConsoleConfigApplicationService service;

  @BeforeEach
  void setUp() {
    when(tenantGuard.resolveTenant(any())).thenReturn(TENANT);
    lenient()
        .when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req", "trace", TENANT, "admin", "idem", "127.0.0.1"));
    lenient().when(secretPayloadProtector.protect(any())).thenReturn("{\"format\":\"encrypted\"}");
    lenient()
        .when(configReleaseApplyService.canonicalType(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(configReleaseMapper.updateConfigReleaseStatus(anyMap())).thenReturn(1);
    lenient().when(configReleaseMapper.selectLatestVersionNo(anyMap())).thenReturn(1);
  }

  // ── 配置发布单查询 ─────────────────────────────────────────────────────

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
    assertThat(list.get(0).grayScopeJson()).isEqualTo("{}");
    assertThat(list.get(0).configPayloadJson()).isEqualTo("{}");
  }

  @Test
  void shouldReturnMachineReadableJson_withoutHtmlEscaping() {
    ConfigReleaseEntity entity = release(1L, "JOB", "k", 1);
    entity.setGrayScope("{\"tenant\":\"t1\"}");
    entity.setConfigPayload("{\"jobCode\":\"job-1\"}");
    when(configReleaseMapper.selectByQuery(any())).thenReturn(List.of(entity));

    ConfigReleaseQueryRequest request = new ConfigReleaseQueryRequest();
    request.setTenantId(TENANT);

    ConsoleConfigReleaseResponse response = service.configReleases(request).getFirst();

    assertThat(response.grayScopeJson()).isEqualTo("{\"tenant\":\"t1\"}");
    assertThat(response.configPayloadJson()).isEqualTo("{\"jobCode\":\"job-1\"}");
  }

  // ── 创建配置发布单 ─────────────────────────────────────────────────────

  @Test
  void shouldCreateConfigRelease_andIncrementVersion() {
    when(configReleaseMapper.selectLatestVersionNo(anyMap())).thenReturn(2);
    ConfigReleaseUpsertRequest req = upsertRequest();

    Long versionNo = service.createConfigRelease(req);

    assertThat(versionNo).isEqualTo(3L);
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(configReleaseMapper).insertConfigRelease(captor.capture());
    assertThat(captor.getValue()).containsEntry("grayScopeJson", null);
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

  // ── 回滚 ───────────────────────────────────────────────────────────────

  @Test
  void shouldThrow_whenLoadReleaseNotFound() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(null);

    ConfigReleaseActionRequest req = actionRequest();
    assertThatThrownBy(() -> service.rollbackConfigRelease(99L, req))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldReject_whenRollbackDraftRelease() {
    // 回归:rollback 只能作用于已上线发布,DRAFT 无可回滚内容。release() 默认 DRAFT。
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));

    assertThatThrownBy(() -> service.rollbackConfigRelease(10L, actionRequest()))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  void shouldRollbackConfigRelease_andSetRolledBackAt() {
    // rollback 只能作用于已上线发布(状态机守卫),用 PUBLISHED release。
    ConfigReleaseEntity published = release(10L, "JOB", "k", 1);
    published.setConfigStatus(ConfigLifecycleStatus.PUBLISHED.code());
    when(configReleaseMapper.selectById(anyMap())).thenReturn(published);
    ConfigReleaseEntity previous = release(9L, "JOB", "k", 0);
    previous.setConfigStatus(ConfigLifecycleStatus.PUBLISHED.code());
    when(configReleaseMapper.selectPreviousEffective(anyMap())).thenReturn(previous);

    ConfigReleaseActionRequest req = actionRequest();
    String status = service.rollbackConfigRelease(10L, req);

    assertThat(status).isEqualTo(ConfigLifecycleStatus.ROLLED_BACK.code());
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(configReleaseMapper).updateConfigReleaseStatus(captor.capture());
    assertThat(captor.getValue().get("rolledBackAt")).isNotNull();
    assertThat(captor.getValue().get("publishedAt")).isNull();
    verify(configReleaseApplyService).apply(eq(previous), eq("admin"), eq("config-rollback-10"));
  }

  @Test
  void shouldReject_whenExpectedVersionIsStale() {
    ConfigReleaseEntity published = release(10L, "JOB", "k", 2);
    published.setConfigStatus(ConfigLifecycleStatus.PUBLISHED.code());
    when(configReleaseMapper.selectById(anyMap())).thenReturn(published);

    assertThatThrownBy(() -> service.rollbackConfigRelease(10L, actionRequest()))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
    verify(configReleaseMapper, never()).updateConfigReleaseStatus(anyMap());
  }

  @Test
  void shouldReject_whenReleaseIsNotLatestVersion() {
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release(10L, "JOB", "k", 1));
    when(configReleaseMapper.selectLatestVersionNo(anyMap())).thenReturn(2);

    ConfigReleaseEntity published = release(10L, "JOB", "k", 1);
    published.setConfigStatus(ConfigLifecycleStatus.PUBLISHED.code());
    when(configReleaseMapper.selectById(anyMap())).thenReturn(published);
    assertThatThrownBy(() -> service.rollbackConfigRelease(10L, actionRequest()))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
    verify(configReleaseMapper, never()).updateConfigReleaseStatus(anyMap());
  }

  @Test
  void shouldReject_whenStatusCasLosesRace() {
    ConfigReleaseEntity published = release(10L, "JOB", "k", 1);
    published.setConfigStatus(ConfigLifecycleStatus.PUBLISHED.code());
    when(configReleaseMapper.selectById(anyMap())).thenReturn(published);
    ConfigReleaseEntity previous = release(9L, "JOB", "k", 0);
    previous.setConfigStatus(ConfigLifecycleStatus.PUBLISHED.code());
    when(configReleaseMapper.selectPreviousEffective(anyMap())).thenReturn(previous);
    when(configReleaseMapper.updateConfigReleaseStatus(anyMap())).thenReturn(0);

    assertThatThrownBy(() -> service.rollbackConfigRelease(10L, actionRequest()))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  // ── 密钥版本查询与轮换 ─────────────────────────────────────────────────

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
    assertThat(list.getFirst().secretRef()).isEqualTo("ref");
    assertThat(list.getFirst().secretPayloadJson()).isEqualTo("{\"redacted\":true}");
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
    assertThat(captor.getValue()).containsEntry("secretStatus", "PUBLISHED");
    assertThat(captor.getValue()).containsEntry("currentVersion", true);
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
    assertThat(captor.getValue())
        .containsEntry("secretStatus", ConfigLifecycleStatus.PUBLISHED.code());
  }

  // ── 配置变更日志 ───────────────────────────────────────────────────────

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

  // ── 详情、依赖关系与差异 ───────────────────────────────────────────────

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
  void shouldEncryptLegacyObjectSecretPayload() {
    SecretVersionRotateRequest req = rotateRequest();
    req.setSecretPayloadJson(null);
    req.setSecretPayload(Map.of("k", "legacy"));

    service.rotateSecretVersion(req);

    verify(secretPayloadProtector).protect("{\"k\":\"legacy\"}");
  }

  @Test
  void shouldReturnSecretVersionDetail() {
    when(secretVersionMapper.selectById(anyMap())).thenReturn(secret(7L, "ref"));
    ConsoleSecretVersionResponse resp = service.secretVersionDetail(TENANT, 7L);
    assertThat(resp.id()).isEqualTo(7L);
    assertThat(resp.secretPayloadJson()).isEqualTo("{\"redacted\":true}");
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

    ConfigDependenciesResponse result = service.configDependencies(TENANT, "QUEUE", "q1");

    assertThat(result.dependentJobCount()).isEqualTo(1);
    assertThat(result.configType()).isEqualTo("QUEUE");
  }

  @Test
  void shouldReturnConfigDependencies_forCalendarType() {
    when(dashboardQueryMapper.jobsByCalendarCode(TENANT, "c1"))
        .thenReturn(List.of(new ConfigDependentView(2L, "JOB-2", null)));

    ConfigDependenciesResponse result =
        service.configDependencies(TENANT, "BUSINESS_CALENDAR", "c1");
    assertThat(result.dependentJobCount()).isEqualTo(1);
  }

  @Test
  void shouldReturnConfigDependencies_forWindowType() {
    when(dashboardQueryMapper.jobsByWindowCode(TENANT, "w1")).thenReturn(List.of());
    ConfigDependenciesResponse result = service.configDependencies(TENANT, "BATCH_WINDOW", "w1");
    assertThat(result.dependentJobCount()).isZero();
  }

  @Test
  void shouldReturnConfigDependencies_forWorkerGroupType() {
    when(dashboardQueryMapper.jobsByWorkerGroup(TENANT, "g1"))
        .thenReturn(List.of(new ConfigDependentView(3L, "JOB-3", "Three")));
    ConfigDependenciesResponse result = service.configDependencies(TENANT, "WORKER_GROUP", "g1");
    assertThat(result.dependentJobCount()).isEqualTo(1);
  }

  @Test
  void shouldReturnEmptyDependencies_forUnknownType() {
    ConfigDependenciesResponse result = service.configDependencies(TENANT, "UNKNOWN", "x");
    assertThat(result.dependentJobCount()).isZero();
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

    ConfigReleaseDiffResponse result = service.diffConfigReleases(TENANT, 1L, 2L);

    assertThat(result.payloadChanged()).isTrue();
    assertThat(result.grayScopeChanged()).isTrue();
    assertThat(result.statusChanged()).isTrue();
    assertThat(result.payloadA()).isNotNull();
    assertThat(result.payloadB()).isNotNull();
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

    ConfigReleaseDiffResponse result = service.diffConfigReleases(TENANT, 1L, 2L);

    assertThat(result.payloadChanged()).isTrue(); // null vs {"a":2}
    assertThat(result.grayScopeChanged()).isTrue(); // {"x":1} vs null
  }

  @Test
  void shouldDiffConfigReleases_whenPayloadSame() {
    ConfigReleaseEntity a = release(1L, "JOB", "k", 1);
    ConfigReleaseEntity b = release(2L, "JOB", "k", 2);
    when(configReleaseMapper.selectById(anyMap())).thenReturn(a, b);

    ConfigReleaseDiffResponse result = service.diffConfigReleases(TENANT, 1L, 2L);

    assertThat(result.payloadChanged()).isFalse();
    assertThat(result.grayScopeChanged()).isFalse();
    assertThat(result.statusChanged()).isFalse();
  }

  // ── 测试辅助方法 ───────────────────────────────────────────────────────

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
    req.setEffectiveFromAt("2026-01-01T00:00:00Z");
    req.setEffectiveToAt("2026-12-31T23:59:59Z");
    req.setReason("init");
    return req;
  }

  private static ConfigReleaseActionRequest actionRequest() {
    ConfigReleaseActionRequest req = new ConfigReleaseActionRequest();
    req.setTenantId(TENANT);
    req.setReason("action");
    req.setExpectedVersionNo(1);
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
    req.setReason("rotate");
    return req;
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }

  // 避免优化器裁剪调用后产生 eq / never 未使用导入告警。
  @SuppressWarnings("unused")
  private void _refs() {
    eq(0);
    never();
  }
}
