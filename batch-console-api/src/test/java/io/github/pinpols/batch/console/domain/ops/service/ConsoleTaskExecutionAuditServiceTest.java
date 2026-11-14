package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.ops.dto.TaskExecutionAuditResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.JobTaskMapper;
import io.github.pinpols.batch.console.domain.ops.view.JobTaskExecutionAuditView;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务执行审计:返回生效命令与结果, 环境变量与凭据脱敏, 缺失或跨租户抛业务异常")
class ConsoleTaskExecutionAuditServiceTest {

  @Mock
  private JobTaskMapper jobTaskMapper;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private ConsoleTaskExecutionAuditService service() {
    return new ConsoleTaskExecutionAuditService(jobTaskMapper, objectMapper, tenantId -> tenantId);
  }

  private static JobTaskExecutionAuditView task(String parameters, String result, String error) {
    JobTaskExecutionAuditView view = new JobTaskExecutionAuditView();
    view.setId(7L);
    view.setJobInstanceId(70L);
    view.setJobCode("NIGHTLY_SHELL");
    view.setTaskStatus("FAILED");
    view.setAssignedWorkerCode("atomic-1");
    view.setEffectiveParameters(parameters);
    view.setResultSummary(result);
    view.setErrorCode("SHELL_EXIT_NON_ZERO");
    view.setErrorMessage(error);
    view.setStartedAt(Instant.parse("2026-11-14T01:00:00Z"));
    view.setFinishedAt(Instant.parse("2026-11-14T01:00:05Z"));
    return view;
  }

  @Test
  @DisplayName("命令可见:shell 命令与参数原样返回, 结果与执行者回填")
  @SuppressWarnings("unchecked")
  void shouldReturnCommandAndOutcome_whenTaskExists() {
    when(jobTaskMapper.selectExecutionAuditByTenantAndId("tx", 7L))
        .thenReturn(task(
            "{\"command\":\"/opt/jobs/rebuild.sh\",\"args\":[\"--day\",\"2026-11-13\"]}",
            "{\"exitCode\":2}",
            "exit code 2"));

    TaskExecutionAuditResponse response = service().getExecutionAudit("tx", 7L);

    Map<String, Object> parameters = (Map<String, Object>) response.effectiveParameters();
    assertThat(parameters).containsEntry("command", "/opt/jobs/rebuild.sh");
    assertThat(response.jobCode()).isEqualTo("NIGHTLY_SHELL");
    assertThat(response.outcome().assignedWorkerCode()).isEqualTo("atomic-1");
    assertThat((Map<String, Object>) response.outcome().resultSummary())
        .containsEntry("exitCode", 2);
    assertThat(response.outcome().errorMessage()).isEqualTo("exit code 2");
  }

  @Test
  @DisplayName("脱敏:环境变量与请求头只留键名, 认证块与敏感键整体遮蔽")
  @SuppressWarnings("unchecked")
  void shouldMaskEnvHeadersAuthAndSensitiveKeys() {
    when(jobTaskMapper.selectExecutionAuditByTenantAndId("tx", 7L))
        .thenReturn(task("""
                {"env": {"DB_URL": "jdbc:x", "MODE": "full"},
                 "headers": {"X-Trace": "abc"},
                 "auth": {"type": "basic", "user": "u"},
                 "apiToken": "plain-token", "sql": "select 1"}
                """, null, null));

    Map<String, Object> parameters =
        (Map<String, Object>) service().getExecutionAudit("tx", 7L).effectiveParameters();

    assertThat((Map<String, Object>) parameters.get("env"))
        .containsOnlyKeys("DB_URL", "MODE")
        .containsValues(SecretMasking.MASKED_VALUE);
    assertThat((Map<String, Object>) parameters.get("env")).doesNotContainValue("full");
    assertThat((Map<String, Object>) parameters.get("headers"))
        .containsEntry("X-Trace", SecretMasking.MASKED_VALUE);
    assertThat(parameters)
        .containsEntry("auth", SecretMasking.MASKED_VALUE)
        .containsEntry("apiToken", SecretMasking.MASKED_VALUE)
        .containsEntry("sql", "select 1");
  }

  @Test
  @DisplayName("缺省:未记录参数或结果时返回空值, 坏 JSON 降级为空")
  void shouldReturnNull_whenJsonMissingOrInvalid() {
    when(jobTaskMapper.selectExecutionAuditByTenantAndId("tx", 7L))
        .thenReturn(task(null, "{not-json", null));

    TaskExecutionAuditResponse response = service().getExecutionAudit("tx", 7L);

    assertThat(response.effectiveParameters()).isNull();
    assertThat(response.outcome().resultSummary()).isNull();
    assertThat(response.outcome().errorMessage()).isNull();
  }

  @Test
  @DisplayName("缺失:任务不存在或不属于该租户时抛业务异常")
  void shouldThrowNotFound_whenTaskMissing() {
    when(jobTaskMapper.selectExecutionAuditByTenantAndId("tx", 8L)).thenReturn(null);

    assertThatThrownBy(() -> service().getExecutionAudit("tx", 8L))
        .isInstanceOf(BizException.class);
  }
}
