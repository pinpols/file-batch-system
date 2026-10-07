package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("补偿命令失败恢复集成:验证真实数据库与真实服务下补偿中途失败时命令置失败并留下错误信息,同一目标再次提交可成功且记录累计两条")
class CompensationFailureRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "it-comp";
  private static final Long PARTITION_ID = 880001L;

  @Autowired
  private CompensationService compensationService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private RetryGovernanceService retryGovernanceService;

  @Test
  @DisplayName("补偿执行中途失败时命令置为失败并落库错误信息,失败后同一目标再次提交可成功恢复且累计两条命令记录")
  void shouldPersistFailedCommandAndRecover_whenSameTargetResubmitted() {
    doThrow(new IllegalStateException("simulated mid-compensation failure"))
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), org.mockito.ArgumentMatchers.anyString());

    assertThatThrownBy(() -> compensationService.submit(command("first attempt")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated mid-compensation failure");

    Map<String, Object> failed = latestCommand();
    assertThat(failed).containsEntry("command_status", CompensationCommandStatus.FAILED.code());
    assertThat(failed.get("error_message").toString())
        .contains("simulated mid-compensation failure");

    reset(retryGovernanceService);
    doNothing()
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), org.mockito.ArgumentMatchers.anyString());

    String recoveredCommandNo = compensationService.submit(command("recovery attempt"));

    assertThat(recoveredCommandNo).isNotBlank();
    Map<String, Object> recovered = latestCommand();
    assertThat(recovered).containsEntry("command_no", recoveredCommandNo);
    assertThat(recovered).containsEntry("command_status", CompensationCommandStatus.SUCCESS.code());
    assertThat(jdbcTemplate.queryForObject("""
                select count(*)::int
                from batch.compensation_command
                where tenant_id = ? and compensation_type = 'PARTITION' and target_id = ?
                """, Integer.class, TENANT, PARTITION_ID))
        .isEqualTo(2);
  }

  private static CompensationSubmitCommand command(String reason) {
    return CompensationSubmitCommand.builder()
        .tenantId(TENANT)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID)
        .reason(reason)
        .operatorId("it")
        .traceId("trace-comp-recovery")
        .build();
  }

  private Map<String, Object> latestCommand() {
    return jdbcTemplate.queryForMap("""
        select command_no, command_status, error_message
        from batch.compensation_command
        where tenant_id = ? and compensation_type = 'PARTITION' and target_id = ?
        order by id desc
        limit 1
        """, TENANT, PARTITION_ID);
  }
}
