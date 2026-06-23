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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompensationFailureRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "it-comp";
  private static final Long PARTITION_ID = 880001L;

  @Autowired private CompensationService compensationService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private RetryGovernanceService retryGovernanceService;

  @Test
  void failedCompensationCommandIsPersistedAndSameTargetCanRecoverOnNextSubmit() {
    doThrow(new IllegalStateException("simulated mid-compensation failure"))
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), org.mockito.ArgumentMatchers.anyString());

    assertThatThrownBy(() -> compensationService.submit(command("first attempt")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated mid-compensation failure");

    Map<String, Object> failed = latestCommand();
    assertThat(failed.get("command_status")).isEqualTo(CompensationCommandStatus.FAILED.code());
    assertThat(failed.get("error_message").toString())
        .contains("simulated mid-compensation failure");

    reset(retryGovernanceService);
    doNothing()
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), org.mockito.ArgumentMatchers.anyString());

    String recoveredCommandNo = compensationService.submit(command("recovery attempt"));

    assertThat(recoveredCommandNo).isNotBlank();
    Map<String, Object> recovered = latestCommand();
    assertThat(recovered.get("command_no")).isEqualTo(recoveredCommandNo);
    assertThat(recovered.get("command_status")).isEqualTo(CompensationCommandStatus.SUCCESS.code());
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)::int
                from batch.compensation_command
                where tenant_id = ? and compensation_type = 'PARTITION' and target_id = ?
                """,
                Integer.class,
                TENANT,
                PARTITION_ID))
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
    return jdbcTemplate.queryForMap(
        """
        select command_no, command_status, error_message
        from batch.compensation_command
        where tenant_id = ? and compensation_type = 'PARTITION' and target_id = ?
        order by id desc
        limit 1
        """,
        TENANT,
        PARTITION_ID);
  }
}
