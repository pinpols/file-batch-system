package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseApprovalSubmitRequest;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleConfigApprovalDetailResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalInsertParam;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalTransitionParam;
import io.github.pinpols.batch.console.domain.ops.view.ConfigApprovalRow;
import io.github.pinpols.batch.console.domain.param.ConfigChangeLogInsertParam;
import io.github.pinpols.batch.console.domain.param.ConfigReleaseStatusUpdateParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigChangeLogMapper;
import io.github.pinpols.batch.console.mapper.ConfigReleaseMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("控制台配置审批应用服务: 提交校验,审批通过与自我审批拦截")
class DefaultConsoleConfigApprovalApplicationServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private ConfigReleaseMapper configReleaseMapper;
  private ConfigApprovalMapper configApprovalMapper;
  private ConfigChangeLogMapper configChangeLogMapper;
  private ConfigReleaseApplyService configReleaseApplyService;
  private ConsoleRequestMetadataResolver requestMetadataResolver;
  private DefaultConsoleConfigApprovalApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    configReleaseMapper = mock(ConfigReleaseMapper.class);
    configApprovalMapper = mock(ConfigApprovalMapper.class);
    configChangeLogMapper = mock(ConfigChangeLogMapper.class);
    configReleaseApplyService = mock(ConfigReleaseApplyService.class);
    requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    service = new DefaultConsoleConfigApprovalApplicationService(
        tenantGuard,
        configReleaseMapper,
        configApprovalMapper,
        configChangeLogMapper,
        configReleaseApplyService,
        requestMetadataResolver);
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", "t1", "admin", "idem", "127.0.0.1"));
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    when(configReleaseMapper.selectLatestVersionNo(any(), any(), any())).thenReturn(1);
    when(configReleaseMapper.updateConfigReleaseStatus(any(ConfigReleaseStatusUpdateParam.class)))
        .thenReturn(1);
    lenient()
        .when(configReleaseApplyService.canonicalType(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("审批详情保留所有字段和时间, 嵌套空字段按原契约省略")
  void shouldPreserveApprovalWireFields_whenDetailHasApproval() throws Exception {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    when(configReleaseMapper.selectById("t1", 10L)).thenReturn(release);
    Instant requested = Instant.parse("2026-07-11T02:00:00Z");
    Instant reviewed = Instant.parse("2026-07-12T03:00:00Z");
    when(configApprovalMapper.selectLatestByRelease("t1", 10L))
        .thenReturn(new ConfigApprovalRow(
            9L,
            "t1",
            10L,
            "APPROVED",
            "requester",
            requested,
            "reviewer",
            reviewed,
            "done",
            null,
            requested,
            reviewed));

    ConsoleConfigApprovalDetailResponse result = service.detail("t1", 10L);
    assertThat(result.approval().requestedAt()).isEqualTo(requested);
    assertThat(result.approval().reviewedAt()).isEqualTo(reviewed);
    JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(result);
    assertThat(json.size()).isEqualTo(6);
    assertThat(json.path("releaseId").asLong()).isEqualTo(10L);
    assertThat(json.path("configKey").asText()).isEqualTo("job-1");
    JsonNode approval = json.path("approval");
    assertThat(approval.size()).isEqualTo(11);
    assertThat(approval.path("id").asLong()).isEqualTo(9L);
    assertThat(approval.path("tenantId").asText()).isEqualTo("t1");
    assertThat(approval.path("releaseId").asLong()).isEqualTo(10L);
    assertThat(approval.path("approvalStatus").asText()).isEqualTo("APPROVED");
    assertThat(approval.path("requestedBy").asText()).isEqualTo("requester");
    assertThat(approval.path("reviewedBy").asText()).isEqualTo("reviewer");
    assertThat(approval.path("reviewComment").asText()).isEqualTo("done");
    assertThat(approval.has("requestedAt")).isTrue();
    assertThat(approval.has("reviewedAt")).isTrue();
    assertThat(approval.has("createdAt")).isTrue();
    assertThat(approval.has("updatedAt")).isTrue();
    assertThat(approval.has("expiredAt")).isFalse();
  }

  @Test
  @DisplayName("无审批时详情保留显式 approval 空值")
  void shouldPreserveExplicitNull_whenDetailHasNoApproval() {
    when(configReleaseMapper.selectById("t1", 10L))
        .thenReturn(release(ConfigLifecycleStatus.DRAFT.code()));
    when(configApprovalMapper.selectLatestByRelease("t1", 10L)).thenReturn(null);
    JsonNode json =
        new ObjectMapper().findAndRegisterModules().valueToTree(service.detail("t1", 10L));
    assertThat(json.has("approval")).isTrue();
    assertThat(json.path("approval").isNull()).isTrue();
  }

  @Test
  @DisplayName("提交配置审批时,先校验载荷再写入审批记录与变更日志")
  void shouldSubmitApproval() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.DRAFT.code());
    when(configReleaseMapper.selectById(any(), any())).thenReturn(release);
    when(configApprovalMapper.selectLatestByRelease("t1", 10L)).thenReturn(null);

    ConfigReleaseApprovalSubmitRequest request = new ConfigReleaseApprovalSubmitRequest();
    request.setTenantId("t1");
    request.setReason("need approval");

    service.submit(10L, request);

    verify(configReleaseApplyService).validate("JOB", "job-1", "{\"jobCode\":\"job-1\"}");
    verify(configApprovalMapper).insert(any(ConfigApprovalInsertParam.class));
    verify(configReleaseMapper)
        .updateConfigReleaseStatus(any(ConfigReleaseStatusUpdateParam.class));
    verify(configChangeLogMapper).insertConfigChangeLog(any(ConfigChangeLogInsertParam.class));
  }

  @Test
  @DisplayName("草稿载荷缺失时,提交被拒绝且不写审批记录与发布状态")
  void shouldRejectSubmit_whenDraftPayloadIsMissing() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.DRAFT.code());
    release.setConfigPayload(null);
    when(configReleaseMapper.selectById(any(), any())).thenReturn(release);
    doThrow(new IllegalArgumentException("config payload is required"))
        .when(configReleaseApplyService)
        .validate(eq("JOB"), eq("job-1"), isNull());

    ConfigReleaseApprovalSubmitRequest request = new ConfigReleaseApprovalSubmitRequest();
    request.setTenantId("t1");

    Assertions.assertThatThrownBy(() -> service.submit(10L, request))
        .isInstanceOf(IllegalArgumentException.class);
    verify(configApprovalMapper, never()).insert(any(ConfigApprovalInsertParam.class));
    verify(configReleaseMapper, never())
        .updateConfigReleaseStatus(any(ConfigReleaseStatusUpdateParam.class));
  }

  @Test
  @DisplayName("审批通过后,执行发布单应用并同步更新状态与变更日志")
  void shouldApproveAndPublishRelease() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    when(configApprovalMapper.selectById("t1", 9L))
        .thenReturn(pendingApproval(9L, 10L, "requester"));
    when(configReleaseMapper.selectById(any(), any())).thenReturn(release);
    when(configApprovalMapper.transition(any(ConfigApprovalTransitionParam.class)))
        .thenReturn(1);
    when(configApprovalMapper.selectLatestByRelease("t1", 10L))
        .thenReturn(new ConfigApprovalRow(
            9L, "t1", 10L, "APPROVED", "requester", null, "admin", null, null, null, null, null));

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");
    request.setReason("approved");

    ConsoleConfigApprovalDetailResponse result = service.approve(9L, request);

    assertThat(result.configStatus()).isEqualTo(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    verify(configApprovalMapper).transition(any(ConfigApprovalTransitionParam.class));
    verify(configReleaseApplyService).apply(release, "admin", "config-approval-9");
    verify(configReleaseMapper)
        .updateConfigReleaseStatus(any(ConfigReleaseStatusUpdateParam.class));
    verify(configChangeLogMapper).insertConfigChangeLog(any(ConfigChangeLogInsertParam.class));
  }

  @Test
  @DisplayName("审批人正是提交人本人时,拦截自我审批并抛出业务异常")
  void shouldRejectSelfApproval() {
    when(configApprovalMapper.selectById("t1", 9L)).thenReturn(pendingApproval(9L, 10L, "admin"));

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");

    Assertions.assertThatThrownBy(() -> service.approve(9L, request))
        .isInstanceOf(BizException.class);
  }

  private static ConfigApprovalRow pendingApproval(long id, long releaseId, String requestedBy) {
    return new ConfigApprovalRow(
        id, "t1", releaseId, "PENDING", requestedBy, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("运行时应用失败时,不更新发布状态并向上抛出异常")
  void shouldNotPublishRelease_whenRuntimeApplyFails() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    when(configApprovalMapper.selectById("t1", 9L))
        .thenReturn(pendingApproval(9L, 10L, "requester"));
    when(configReleaseMapper.selectById(any(), any())).thenReturn(release);
    when(configApprovalMapper.transition(any(ConfigApprovalTransitionParam.class)))
        .thenReturn(1);
    doThrow(new IllegalStateException("apply failed"))
        .when(configReleaseApplyService)
        .apply(release, "admin", "config-approval-9");

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");

    Assertions.assertThatThrownBy(() -> service.approve(9L, request))
        .isInstanceOf(IllegalStateException.class);
    verify(configReleaseMapper, never())
        .updateConfigReleaseStatus(any(ConfigReleaseStatusUpdateParam.class));
  }

  private ConfigReleaseEntity release(String status) {
    ConfigReleaseEntity release = new ConfigReleaseEntity();
    release.setId(10L);
    release.setTenantId("t1");
    release.setConfigType("JOB");
    release.setConfigKey("job-1");
    release.setConfigPayload("{\"jobCode\":\"job-1\"}");
    release.setVersionNo(1);
    release.setConfigStatus(status);
    return release;
  }
}
