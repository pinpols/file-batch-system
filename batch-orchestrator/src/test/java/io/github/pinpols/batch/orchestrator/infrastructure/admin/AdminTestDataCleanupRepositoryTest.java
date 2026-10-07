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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("测试数据清理仓储的前缀删除与精确租户删除行为,覆盖删除链目标,返回值汇总与租户入参校验")
class AdminTestDataCleanupRepositoryTest {

  private final AdminTestDataCleanupMapper mapper = mock(AdminTestDataCleanupMapper.class);
  private AdminTestDataCleanupRepository repository;

  @BeforeEach
  void setUp() {
    repository = new AdminTestDataCleanupRepository(mapper);
  }

  @Test
  @DisplayName("合法前缀触发完整外键删除链,汇总返回各删除目标的影响行数且父引用清理项不进入结果")
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
  @DisplayName("清理前缀仅作为绑定参数传入并在末尾追加通配符,不拼接进语句文本")
  void shouldPassEscapedPrefixOnlyAsBoundValue() {
    ArgumentCaptor<String> prefixCaptor = ArgumentCaptor.forClass(String.class);

    repository.cleanupByPrefix("test");

    verify(mapper)
        .cleanupByPrefix(eq(CleanupTarget.JOB_INSTANCE), prefixCaptor.capture(), eq("op-test-%"));
    assertThat(prefixCaptor.getValue()).isEqualTo("test-%");
  }

  @Test
  @DisplayName("按精确租户标识清理时覆盖全部删除目标并汇总结果,父引用清理项不进入返回结果")
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
  @DisplayName("租户标识列表为空时拒绝清理并返回参数非法错误码")
  void shouldRejectMissingTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("包含受保护的系统租户标识时拒绝清理并返回参数非法错误码")
  void shouldRejectProtectedTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of("system")))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }
}
