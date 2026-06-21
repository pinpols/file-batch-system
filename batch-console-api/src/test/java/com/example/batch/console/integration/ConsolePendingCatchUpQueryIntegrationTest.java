package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.model.PageRequest;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.job.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.job.mapper.PendingCatchUpMapper;
import com.example.batch.console.domain.job.query.PendingCatchUpQuery;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：catch-up 待审列表 mapper 服务端筛选(jobCode / requestId / bizDate)对真实库的验证。
 *
 * <p>bizDate 是 2026-06-21 为前端 P5 服务端分页新补的精确过滤参数,mapper 用 {@code cast(#{bizDate} as date)} 比对 {@code
 * trigger_request.biz_date}(DATE 列)。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsolePendingCatchUpQueryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PendingCatchUpMapper pendingCatchUpMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void shouldFilterByBizDate() {
    String tenantId = "t-catchup-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "JOB_A", "2026-06-20");
    insertCatchUp(tenantId, "JOB_B", "2026-06-21");
    insertCatchUp(tenantId, "JOB_C", "2026-06-21");

    PendingCatchUpQuery query =
        new PendingCatchUpQuery(
            tenantId, null, null, "2026-06-21", null, new PageRequest(1, 10), null);

    List<PendingCatchUpEntity> rows = pendingCatchUpMapper.selectByQuery(query);
    long total = pendingCatchUpMapper.countByQuery(query);

    assertThat(rows).hasSize(2);
    assertThat(rows).allMatch(r -> "2026-06-21".equals(r.getBizDate().toString()));
    assertThat(total).isEqualTo(2);
  }

  @Test
  void shouldReturnAllWhenBizDateBlank() {
    String tenantId = "t-catchup-all-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "JOB_A", "2026-06-20");
    insertCatchUp(tenantId, "JOB_B", "2026-06-21");

    PendingCatchUpQuery query =
        new PendingCatchUpQuery(tenantId, null, null, null, null, new PageRequest(1, 10), null);

    assertThat(pendingCatchUpMapper.selectByQuery(query)).hasSize(2);
    assertThat(pendingCatchUpMapper.countByQuery(query)).isEqualTo(2);
  }

  @Test
  void shouldFilterByKeywordAcrossColumns() {
    String tenantId = "t-catchup-kw-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "PAYROLL_DAILY", "2026-06-21");
    insertCatchUp(tenantId, "LEDGER_CLOSE", "2026-06-21");

    PendingCatchUpQuery query =
        new PendingCatchUpQuery(
            tenantId, null, null, null, "payroll", new PageRequest(1, 10), null);

    List<PendingCatchUpEntity> rows = pendingCatchUpMapper.selectByQuery(query);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getJobCode()).isEqualTo("PAYROLL_DAILY");
    assertThat(pendingCatchUpMapper.countByQuery(query)).isEqualTo(1);
  }

  @Test
  void shouldCombineBizDateWithJobCode() {
    String tenantId = "t-catchup-combo-" + BatchDateTimeSupport.utcEpochMillis();
    insertCatchUp(tenantId, "JOB_A", "2026-06-21");
    insertCatchUp(tenantId, "JOB_B", "2026-06-21");

    PendingCatchUpQuery query =
        new PendingCatchUpQuery(
            tenantId, "JOB_A", null, "2026-06-21", null, new PageRequest(1, 10), null);

    List<PendingCatchUpEntity> rows = pendingCatchUpMapper.selectByQuery(query);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getJobCode()).isEqualTo("JOB_A");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void insertCatchUp(String tenantId, String jobCode, String bizDate) {
    String requestId = jobCode + "-" + System.nanoTime();
    jdbcTemplate.update(
        """
        INSERT INTO batch.trigger_request
          (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
           request_status, created_at, updated_at)
        VALUES (?, ?, 'CATCH_UP', ?, cast(? as date), ?, 'ACCEPTED', now(), now())
        """,
        tenantId,
        requestId,
        jobCode,
        bizDate,
        tenantId + ":" + requestId);
  }
}
