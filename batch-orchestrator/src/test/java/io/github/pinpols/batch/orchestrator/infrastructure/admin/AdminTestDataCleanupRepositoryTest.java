package io.github.pinpols.batch.orchestrator.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.CleanupTarget;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminTestDataCleanupRepositoryTest {

  private final AdminTestDataCleanupMapper mapper = mock(AdminTestDataCleanupMapper.class);
  private AdminTestDataCleanupRepository repository;

  @BeforeEach
  void setUp() {
    repository = new AdminTestDataCleanupRepository(mapper);
  }

  @Test
  void shouldRunFullFkDeletionChainOnValidPrefix() {
    when(mapper.cleanupByPrefix(any(), anyString(), anyString())).thenReturn(1);

    var result = repository.cleanupByPrefix("e2e");

    assertThat(result)
        .containsEntry("workflow_node_run", 1)
        .containsEntry("compensation_command", 1)
        .containsEntry("approval_command", 1)
        .containsEntry("job_instance", 1)
        .containsEntry("api_key", 1)
        .containsEntry("alert_routing_config", 1)
        .containsEntry("tenant_quota_policy", 1)
        .doesNotContainKey("clear_job_instance_parent");
    verify(mapper)
        .cleanupByPrefix(eq(CleanupTarget.CLEAR_JOB_INSTANCE_PARENT), eq("e2e-%"), eq("op-e2e-%"));
  }

  @Test
  void shouldPassEscapedPrefixOnlyAsBoundValue() {
    ArgumentCaptor<String> prefixCaptor = ArgumentCaptor.forClass(String.class);

    repository.cleanupByPrefix("test");

    verify(mapper)
        .cleanupByPrefix(eq(CleanupTarget.JOB_INSTANCE), prefixCaptor.capture(), eq("op-test-%"));
    assertThat(prefixCaptor.getValue()).isEqualTo("test-%");
  }

  @Test
  void shouldRunExactTenantCleanupTargets() {
    when(mapper.cleanupByTenantIds(any(), any())).thenReturn(1);

    var result = repository.cleanupByExactTenantIds(List.of("td"));

    assertThat(result)
        .containsEntry("pipeline_step_run", 1)
        .containsEntry("file_error_record", 1)
        .containsEntry("tenant", 1)
        .doesNotContainKey("clear_job_instance_parent");
    verify(mapper).cleanupByTenantIds(CleanupTarget.CLEAR_JOB_INSTANCE_PARENT, List.of("td"));
  }

  @Test
  void shouldRejectMissingTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  void shouldRejectProtectedTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of("system")))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }
}
