package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Trigger 请求应按业务日获取结算 fence")
class TriggerRequestSettlementFenceMapperIntegrationTest extends AbstractIntegrationTest {

  private final TriggerRequestMapper mapper;

  private final JdbcTemplate jdbcTemplate;

  private String tenantId;
  private Long workflowDefinitionId;

  @Autowired
  TriggerRequestSettlementFenceMapperIntegrationTest(
      TriggerRequestMapper mapper, JdbcTemplate jdbcTemplate) {
    this.mapper = mapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @BeforeEach
  void setUp() {
    tenantId = "fence-it-" + System.nanoTime();
    String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status)
        values (?, ?, 'ACTIVE')
        """;
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, calendar_code)
        values (?, ?, ?, 'TEST_CAL') returning id
        """;
    workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        "wf-" + tenantId,
        "Workflow " + tenantId);
  }

  @Test
  @DisplayName("无业务日或无日历时返回无操作标记，不因 SQL 零行映射为 null")
  void shouldMissingFenceInputsReturnNoOpValue() {
    assertThat(mapper.acquireBatchDaySettlementFence(tenantId, workflowDefinitionId, null, null))
        .isZero();
    assertThat(mapper.acquireBatchDaySettlementFence(
            tenantId, null, "missing-job", LocalDate.of(2026, 10, 15)))
        .isZero();
  }

  @Test
  @DisplayName("有日历和业务日时获取稳定的事务级 fence key")
  void shouldValidFenceInputsReturnAdvisoryLockKey() {
    LocalDate bizDate = LocalDate.of(2026, 10, 15);

    Long first =
        mapper.acquireBatchDaySettlementFence(tenantId, workflowDefinitionId, null, bizDate);
    Long second =
        mapper.acquireBatchDaySettlementFence(tenantId, workflowDefinitionId, null, bizDate);

    assertThat(first).isNotNull().isNotZero();
    assertThat(second).isEqualTo(first);
  }
}
