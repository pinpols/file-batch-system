package com.example.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ConfigReleaseMapper;
import com.example.batch.console.web.request.config.ConfigApprovalActionRequest;
import com.example.batch.console.web.request.config.ConfigReleaseApprovalSubmitRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultConsoleConfigApprovalApplicationServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private ConfigReleaseMapper configReleaseMapper;
  private ConfigApprovalMapper configApprovalMapper;
  private ConfigChangeLogMapper configChangeLogMapper;
  private DefaultConsoleConfigApprovalApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    configReleaseMapper = mock(ConfigReleaseMapper.class);
    configApprovalMapper = mock(ConfigApprovalMapper.class);
    configChangeLogMapper = mock(ConfigChangeLogMapper.class);
    service =
        new DefaultConsoleConfigApprovalApplicationService(
            tenantGuard, configReleaseMapper, configApprovalMapper, configChangeLogMapper);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  void shouldSubmitApproval() {
    ConfigReleaseEntity release = release(ConfigLifecycleStatus.DRAFT.code());
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release);
    when(configApprovalMapper.selectLatestByRelease("t1", 10L)).thenReturn(null);

    ConfigReleaseApprovalSubmitRequest request = new ConfigReleaseApprovalSubmitRequest();
    request.setTenantId("t1");
    request.setOperatorId("admin");
    request.setReason("need approval");

    service.submit(10L, request);

    verify(configApprovalMapper).insert(anyMap());
    verify(configReleaseMapper).updateConfigReleaseStatus(anyMap());
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  @Test
  void shouldApproveAndPublishRelease() {
    ConfigReleaseEntity release = release("PENDING_APPROVAL");
    when(configApprovalMapper.selectById("t1", 9L))
        .thenReturn(
            Map.of(
                "id", 9L,
                "releaseId", 10L,
                "approvalStatus", "PENDING"));
    when(configReleaseMapper.selectById(anyMap())).thenReturn(release);
    when(configApprovalMapper.approve(anyMap())).thenReturn(1);
    when(configApprovalMapper.selectLatestByRelease("t1", 10L))
        .thenReturn(Map.of("approvalStatus", "APPROVED"));

    ConfigApprovalActionRequest request = new ConfigApprovalActionRequest();
    request.setTenantId("t1");
    request.setOperatorId("approver");
    request.setReason("approved");

    Map<String, Object> result = service.approve(9L, request);

    assertThat(result).containsEntry("configStatus", "PENDING_APPROVAL");
    verify(configApprovalMapper).approve(anyMap());
    verify(configReleaseMapper).updateConfigReleaseStatus(anyMap());
    verify(configChangeLogMapper).insertConfigChangeLog(anyMap());
  }

  private ConfigReleaseEntity release(String status) {
    ConfigReleaseEntity release = new ConfigReleaseEntity();
    release.setId(10L);
    release.setTenantId("t1");
    release.setConfigType("JOB");
    release.setConfigKey("job-1");
    release.setVersionNo(1);
    release.setConfigStatus(status);
    return release;
  }
}
