package com.example.batch.orchestrator.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AdminTestDataCleanupRepositoryTest {

  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private AdminTestDataCleanupRepository repository;

  @BeforeEach
  void setUp() {
    repository = new AdminTestDataCleanupRepository(jdbc);
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
  }

  @Test
  void shouldRunFullFkDeletionChainOnValidPrefix() {
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

    var result = repository.cleanupByPrefix("e2e");

    assertThat(result)
        .containsEntry("workflow_node_run", 1)
        .containsEntry("workflow_run", 1)
        .containsEntry("compensation_command", 1)
        .containsEntry("pipeline_instance", 1)
        .containsEntry("job_execution_log", 1)
        .containsEntry("job_step_instance", 1)
        .containsEntry("job_task", 1)
        .containsEntry("job_partition", 1)
        .containsEntry("job_instance", 1)
        .containsEntry("workflow_node", 1)
        .containsEntry("workflow_edge", 1)
        .containsEntry("workflow_definition", 1)
        .containsEntry("job_definition", 1)
        .containsEntry("file_channel_config", 1)
        .containsEntry("file_template_config", 1)
        // 新增:三张此前漏清的独立配置表
        .containsEntry("api_key", 1)
        .containsEntry("alert_routing_config", 1)
        .containsEntry("tenant_quota_policy", 1);
    verify(jdbc, atLeast(17)).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void shouldDeleteThreePreviouslyMissedConfigTablesByPrefix() {
    repository.cleanupByPrefix("e2e");

    verify(jdbc)
        .update(
            contains("DELETE FROM batch.api_key WHERE key_name LIKE :p"),
            any(MapSqlParameterSource.class));
    verify(jdbc)
        .update(
            contains("DELETE FROM batch.alert_routing_config WHERE route_code LIKE :p"),
            any(MapSqlParameterSource.class));
    verify(jdbc)
        .update(
            contains("DELETE FROM batch.tenant_quota_policy WHERE policy_code LIKE :p"),
            any(MapSqlParameterSource.class));
  }

  @Test
  void shouldNullOutSelfReferentialParentBeforeDeletingJobInstance() {
    repository.cleanupByPrefix("e2e");

    verify(jdbc)
        .update(
            contains("UPDATE batch.job_instance SET parent_instance_id = NULL"),
            any(MapSqlParameterSource.class));
  }

  @Test
  void shouldMatchPrefixHyphenSuffixOnlyNotBarePrefixSubstring() {
    org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
        org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);

    repository.cleanupByPrefix("test");

    verify(jdbc, atLeastOnce()).update(anyString(), captor.capture());
    MapSqlParameterSource params = captor.getValue();
    assertThat(params.getValue("p")).isEqualTo("test-%");
    assertThat(params.getValue("p")).asString().endsWith("-%");
  }

  @Test
  void shouldRejectProtectedTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of("system")))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }
}
