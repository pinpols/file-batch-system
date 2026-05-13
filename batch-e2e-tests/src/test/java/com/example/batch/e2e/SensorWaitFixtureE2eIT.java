package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.e2e.apps.E2eOrchestratorApplication;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.application.service.workflow.WorkflowGraphValidator;
import com.example.batch.orchestrator.application.service.workflow.WorkflowValidationResult;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/** ADR-028 S5：ta/tb/tc 多租户 seed 至少覆盖一个 WAIT Sensor workflow。 */
@SpringBootTest(
    classes = E2eOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.MULTI_TENANT_SEED})
@Tag("e2e")
class SensorWaitFixtureE2eIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowGraphValidator workflowGraphValidator;

  @Test
  void taSeedContainsValidWaitFileArrivalWorkflow() {
    Long workflowDefinitionId =
        jdbcTemplate.queryForObject(
            """
            select id
              from batch.workflow_definition
             where tenant_id = 'ta'
               and workflow_code = 'TA_WF_WAIT_FILE'
               and version = 1
            """,
            Long.class);

    Map<String, Object> waitNode =
        jdbcTemplate.queryForMap(
            """
            select node_type, node_params
              from batch.workflow_node
             where tenant_id = 'ta'
               and workflow_definition_id = ?
               and node_code = 'WAIT_BANK_FILE'
            """,
            workflowDefinitionId);

    assertThat(waitNode.get("node_type")).isEqualTo("WAIT");
    assertThat(String.valueOf(waitNode.get("node_params")))
        .contains("\"sensor_type\": \"FILE_ARRIVAL\"")
        .contains("\"pattern\": \"settle-*.csv\"")
        .contains("\"timeout_seconds\": 3600");

    Integer edgeCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from batch.workflow_edge
             where tenant_id = 'ta'
               and workflow_definition_id = ?
               and ((from_node_code = 'START' and to_node_code = 'WAIT_BANK_FILE')
                 or (from_node_code = 'WAIT_BANK_FILE' and to_node_code = 'END'))
            """,
            Integer.class,
            workflowDefinitionId);
    assertThat(edgeCount).isEqualTo(2);

    WorkflowValidationResult result = workflowGraphValidator.validate(workflowDefinitionId);
    assertThat(result.errors()).isEmpty();
  }
}
