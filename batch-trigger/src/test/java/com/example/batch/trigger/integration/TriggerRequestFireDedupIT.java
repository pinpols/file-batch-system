package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * trigger_request 加 fire 强约束的集成测试 — 覆盖 design.md §3 R-1 风险兜底。
 *
 * <p>关键验证:
 *
 * <ul>
 *   <li>新字段 INSERT 后能 select 出来
 *   <li>同 (trigger_runtime_state_id, scheduled_fire_time) 重复 INSERT → DuplicateKeyException
 *   <li>历史路径(NULL 字段)不受 partial unique index 影响,可任意 INSERT
 * </ul>
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
class TriggerRequestFireDedupIT extends AbstractIntegrationTest {

  @Autowired private TriggerRequestMapper mapper;
  @Autowired private TriggerRuntimeStateMapper runtimeStateMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long jobDefId;
  private long runtimeStateId;
  private String tenantId;
  private String jobCode;

  @BeforeEach
  void seed() {
    tenantId = "trf-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on conflict do nothing",
        tenantId, tenantId);
    jobDefId = jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type,
          schedule_type, schedule_expr, timezone,
          enabled, created_by, updated_by
        ) values (?, ?, ?, 'GENERAL',
          'CRON', '0 0 * * * ?', 'Asia/Shanghai',
          true, 'it', 'it')
        returning id
        """, Long.class, tenantId, jobCode, jobCode);
    TriggerRuntimeStateEntity rs = new TriggerRuntimeStateEntity();
    rs.setJobDefinitionId(jobDefId);
    rs.setTenantId(tenantId);
    rs.setJobCode(jobCode);
    rs.setNextFireTime(Instant.now().plusSeconds(60));
    runtimeStateMapper.insertOnReconcile(rs);
    runtimeStateId = rs.getId();
  }

  @Test
  void insertWithFireDedupFieldsRoundTrip() {
    Instant scheduled = Instant.now().minusSeconds(5);
    TriggerRequestEntity req = newWheelFireRequest(scheduled);
    int rows = mapper.insert(req);
    assertThat(rows).isEqualTo(1);

    TriggerRequestEntity loaded = mapper.selectByTenantAndRequestId(tenantId, req.getRequestId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getScheduledFireTime()).isEqualTo(scheduled);
    assertThat(loaded.getTriggerRuntimeStateId()).isEqualTo(runtimeStateId);
  }

  @Test
  void duplicateFireRowRejectedByPartialUniqueIndex() {
    Instant scheduled = Instant.now().minusSeconds(5);
    mapper.insert(newWheelFireRequest(scheduled));

    // 同 (runtimeStateId, scheduledFireTime) 第二次 INSERT — 不同 requestId / dedupKey
    TriggerRequestEntity dup = newWheelFireRequest(scheduled);
    dup.setRequestId("req-dup-" + System.nanoTime());
    dup.setDedupKey("dedup-dup-" + System.nanoTime());

    assertThatThrownBy(() -> mapper.insert(dup))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_trigger_request_fire_dedup");
  }

  @Test
  void differentScheduledFireTimeAllowed() {
    mapper.insert(newWheelFireRequest(Instant.now().minusSeconds(60)));
    // 不同 scheduledFireTime → 不同 unique key,允许
    int rows = mapper.insert(newWheelFireRequest(Instant.now().minusSeconds(30)));
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void legacyPathWithNullFireFieldsNotConstrained() {
    // 模拟 API/MANUAL/EVENT 老路径:两个新字段都 NULL
    int first = mapper.insert(newLegacyRequest());
    int second = mapper.insert(newLegacyRequest());
    int third = mapper.insert(newLegacyRequest());
    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(1);
    assertThat(third).isEqualTo(1);
    // partial unique index 不命中 NULL,所以多次 INSERT 都成功
  }

  // ── helpers ─────────────────────────────────────────────

  private TriggerRequestEntity newWheelFireRequest(Instant scheduledFireTime) {
    TriggerRequestEntity req = new TriggerRequestEntity();
    req.setTenantId(tenantId);
    req.setRequestId("req-wheel-" + System.nanoTime());
    req.setTriggerType("SCHEDULED");
    req.setJobCode(jobCode);
    req.setBizDate(LocalDate.now());
    req.setDedupKey("dedup-" + tenantId + ":" + jobCode + ":" + scheduledFireTime.toEpochMilli());
    req.setRequestStatus("ACCEPTED");
    req.setScheduledFireTime(scheduledFireTime);
    req.setTriggerRuntimeStateId(runtimeStateId);
    return req;
  }

  private TriggerRequestEntity newLegacyRequest() {
    TriggerRequestEntity req = new TriggerRequestEntity();
    req.setTenantId(tenantId);
    req.setRequestId("req-legacy-" + System.nanoTime());
    req.setTriggerType("API");
    req.setJobCode(jobCode);
    req.setBizDate(LocalDate.now());
    req.setDedupKey("dedup-legacy-" + System.nanoTime());
    req.setRequestStatus("ACCEPTED");
    req.setScheduledFireTime(null);
    req.setTriggerRuntimeStateId(null);
    return req;
  }
}
