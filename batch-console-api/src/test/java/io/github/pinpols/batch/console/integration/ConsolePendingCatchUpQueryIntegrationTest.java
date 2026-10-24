package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.entity.PendingCatchUpEntity;
import io.github.pinpols.batch.console.domain.job.mapper.PendingCatchUpMapper;
import io.github.pinpols.batch.console.domain.job.query.PendingCatchUpQuery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：Workflow catch-up 待审列表 mapper 服务端筛选(workflowCode / requestId / bizDate)对真实库的验证。
 *
 * <p>bizDate 是 2026-06-21 为前端 P5 服务端分页新补的精确过滤参数,mapper 用 {@code cast(#{bizDate} as date)} 比对 {@code
 * trigger_request.biz_date}(DATE 列)。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("补跑待审列表查询: Workflow 编码,业务日期和关键字的服务端筛选")
class ConsolePendingCatchUpQueryIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private PendingCatchUpMapper pendingCatchUpMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("按业务日期过滤: 只返回该日期的两条记录,计数同为 2")
  void shouldFilterByBizDate() {
    String tenantId = "t-catchup-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "WF_JOB_A", "2026-06-20");
    insertCatchUp(tenantId, "WF_JOB_B", "2026-06-21");
    insertCatchUp(tenantId, "WF_JOB_C", "2026-06-21");

    PendingCatchUpQuery query = PendingCatchUpQuery.builder()
        .tenantId(tenantId)
        .bizDate("2026-06-21")
        .pageRequest(new PageRequest(1, 10))
        .build();

    List<PendingCatchUpEntity> rows = pendingCatchUpMapper.selectByQuery(query);
    long total = pendingCatchUpMapper.countByQuery(query);

    assertThat(rows).hasSize(2);
    assertThat(rows).allMatch(r -> "2026-06-21".equals(r.getBizDate().toString()));
    assertThat(total).isEqualTo(2);
  }

  @Test
  @DisplayName("业务日期留空: 返回该租户全部待审记录,计数一致")
  void shouldReturnAllWhenBizDateBlank() {
    String tenantId = "t-catchup-all-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "WF_JOB_A", "2026-06-20");
    insertCatchUp(tenantId, "WF_JOB_B", "2026-06-21");

    PendingCatchUpQuery query = PendingCatchUpQuery.builder()
        .tenantId(tenantId)
        .pageRequest(new PageRequest(1, 10))
        .build();

    assertThat(pendingCatchUpMapper.selectByQuery(query)).hasSize(2);
    assertThat(pendingCatchUpMapper.countByQuery(query)).isEqualTo(2);
  }

  @Test
  @DisplayName("关键字过滤: 大小写不敏感,只命中 Workflow 编码匹配的一条")
  void shouldFilterByKeywordAcrossColumns() {
    String tenantId = "t-catchup-kw-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "WF_PAYROLL_DAILY", "2026-06-21");
    insertCatchUp(tenantId, "WF_LEDGER_CLOSE", "2026-06-21");

    PendingCatchUpQuery query = PendingCatchUpQuery.builder()
        .tenantId(tenantId)
        .keyword("wf_payroll")
        .pageRequest(new PageRequest(1, 10))
        .build();

    List<PendingCatchUpEntity> rows = pendingCatchUpMapper.selectByQuery(query);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getWorkflowCode()).isEqualTo("WF_PAYROLL_DAILY");
    assertThat(pendingCatchUpMapper.countByQuery(query)).isEqualTo(1);
  }

  @Test
  @DisplayName("业务日期与 Workflow 编码组合过滤: 只返回同时满足的一条")
  void shouldCombineBizDateWithJobCode() {
    String tenantId = "t-catchup-combo-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "WF_JOB_A", "2026-06-21");
    insertCatchUp(tenantId, "WF_JOB_B", "2026-06-21");

    PendingCatchUpQuery query = PendingCatchUpQuery.builder()
        .tenantId(tenantId)
        .workflowCode("WF_JOB_A")
        .bizDate("2026-06-21")
        .pageRequest(new PageRequest(1, 10))
        .build();

    List<PendingCatchUpEntity> rows = pendingCatchUpMapper.selectByQuery(query);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getWorkflowCode()).isEqualTo("WF_JOB_A");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void insertCatchUp(String tenantId, String workflowCode, String bizDate) {
    String requestId = workflowCode + "-" + System.nanoTime();
    String insertWorkflowDefinitionSql = """
        INSERT INTO batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, calendar_code)
        VALUES (?, ?, ?, 'DAG', 'DEFAULT')
        RETURNING id
        """;
    String workflowName = "Catch-up fixture " + requestId;
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowName);
    String insertCatchUpTriggerRequestSql = """
        INSERT INTO batch.trigger_request
          (tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
           request_status, created_at, updated_at)
        VALUES (?, ?, 'CATCH_UP', ?, cast(? as date), ?, 'ACCEPTED', now(), now())
        """;
    String dedupKey = tenantId + ":" + requestId;
    jdbcTemplate.update(
        insertCatchUpTriggerRequestSql,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        dedupKey);
  }
}
