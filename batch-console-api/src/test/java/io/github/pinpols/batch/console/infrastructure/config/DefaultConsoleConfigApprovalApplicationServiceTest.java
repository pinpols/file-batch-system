package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseApprovalSubmitRequest;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleConfigApprovalDetailResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigChangeLogMapper;
import io.github.pinpols.batch.console.mapper.ConfigReleaseMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    when(configReleaseMapper.selectLatestVersionNo(anyMap())).thenReturn(1);
    when(configReleaseMapper.updateConfigReleaseStatus(anyMap())).thenReturn(1);
    lenient()
        .when(configReleaseApplyService.canonicalType(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void shouldSubmitApproval() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.DRAFT.code());
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release);
    when(configApprovalMapper.selectLatestByRelease("t1", 10L)).thenReturn(null);

    ConfigReleaseApprovalSubmitRequest request = new ConfigReleaseApprovalSubmitRequest();
    request.setTenantId("t1");
    request.setReason("need approval");

    service.submit(10L, request);

    verify(configReleaseApplyService).validate("JOB", "job-1", "{\"jobCode\":\"job-1\"}");
    verify(configApprovalMapper).insert(anyMap());
    verify(configReleaseMapper).updateConfigReleaseStatus(anyMap());
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  @Test
  void shouldRejectSubmit_whenDraftPayloadIsMissing() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.DRAFT.code());
    release.setConfigPayload(null);
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release);
    doThrow(new IllegalArgumentException("config payload is required"))
        .when(configReleaseApplyService)
        .validate(eq("JOB"), eq("job-1"), isNull());

    ConfigReleaseApprovalSubmitRequest request = new ConfigReleaseApprovalSubmitRequest();
    request.setTenantId("t1");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(10L, request))
        .isInstanceOf(IllegalArgumentException.class);
    verify(configApprovalMapper, never()).insert(anyMap());
    verify(configReleaseMapper, never()).updateConfigReleaseStatus(anyMap());
  }

  @Test
  void shouldApproveAndPublishRelease() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    when(configApprovalMapper.selectById("t1", 9L))
        .thenReturn(Map.of(
            "id", 9L, "releaseId", 10L, "approvalStatus", "PENDING", "requestedBy", "requester"));
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release);
    when(configApprovalMapper.approve(anyMap())).thenReturn(1);
    when(configApprovalMapper.selectLatestByRelease("t1", 10L))
        .thenReturn(Map.of("approvalStatus", "APPROVED"));

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");
    request.setReason("approved");

    ConsoleConfigApprovalDetailResponse result = service.approve(9L, request);

    assertThat(result.configStatus()).isEqualTo(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    verify(configApprovalMapper).approve(anyMap());
    verify(configReleaseApplyService).apply(release, "admin", "config-approval-9");
    verify(configReleaseMapper).updateConfigReleaseStatus(anyMap());
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  @Test
  void shouldRejectSelfApproval() {
    when(configApprovalMapper.selectById("t1", 9L))
        .thenReturn(Map.of(
            "id", 9L, "releaseId", 10L, "approvalStatus", "PENDING", "requestedBy", "admin"));

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.approve(9L, request))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldNotPublishReleaseWhenRuntimeApplyFails() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.PENDING_APPROVAL.code());
    when(configApprovalMapper.selectById("t1", 9L))
        .thenReturn(Map.of(
            "id", 9L, "releaseId", 10L, "approvalStatus", "PENDING", "requestedBy", "requester"));
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release);
    when(configApprovalMapper.approve(anyMap())).thenReturn(1);
    doThrow(new IllegalStateException("apply failed"))
        .when(configReleaseApplyService)
        .apply(release, "admin", "config-approval-9");

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.approve(9L, request))
        .isInstanceOf(IllegalStateException.class);
    verify(configReleaseMapper, never()).updateConfigReleaseStatus(anyMap());
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
