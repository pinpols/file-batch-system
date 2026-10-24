package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** trigger_misfire_pending Mapper 集成测试 — 覆盖 MANUAL_APPROVAL catch-up 流程的 DB 路径。 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
@DisplayName("misfire 待审批表 Mapper 集成测试:真实库覆盖落库去重、审批驳回与过期收敛的持久化语义")
class TriggerMisfirePendingMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private TriggerMisfirePendingMapper mapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String tenantId;
  private String jobCode;
  private Long workflowDefinitionId;
  private String workflowCode;

  @BeforeEach
  void seed() {
    tenantId = "mfp-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    workflowCode = "wf-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on"
            + " conflict do nothing",
        tenantId,
        tenantId);
    workflowDefinitionId = jdbcTemplate.queryForObject(
        "insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name)"
            + " values (?, ?, ?) returning id",
        Long.class,
        tenantId,
        workflowCode,
        workflowCode);
  }

  @Test
  @DisplayName("新增待审批记录写入 1 行并回填主键,回查状态为 PENDING 且过期时间落在 7 天后")
  void insertPending_thenSelectStatus() {
    Instant scheduled = BatchDateTimeSupport.utcNow().minusSeconds(120);
    TriggerMisfirePendingEntity e = newPending(scheduled);
    int rows = mapper.insertPending(e);
    assertThat(rows).isEqualTo(1);
    assertThat(e.getId()).isNotNull();

    TriggerMisfirePendingEntity loaded = mapper.selectById(e.getId());
    assertThat(loaded.getStatus()).isEqualTo("PENDING");
    assertThat(loaded.getScheduledFireTime()).isEqualTo(scheduled);
    assertThat(loaded.getExpiresAt())
        .isAfter(BatchDateTimeSupport.utcNow().plus(Duration.ofDays(6)));
  }

  @Test
  @DisplayName("同租户同作业同计划触发时刻重复落库时,唯一约束抛 DuplicateKeyException 防重复待审批")
  void insertPendingDuplicate_throwsOnUniqueConstraint() {
    Instant scheduled = BatchDateTimeSupport.utcNow().minusSeconds(120);
    mapper.insertPending(newPending(scheduled));

    assertThatThrownBy(() -> mapper.insertPending(newPending(scheduled)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("按租户查询待审批只返回 PENDING,已审批记录从运营待办列表中消失")
  void selectPendingByTenant_onlyReturnsPending() {
    Instant fireA = BatchDateTimeSupport.utcNow().minusSeconds(180);
    Instant fireB = BatchDateTimeSupport.utcNow().minusSeconds(120);
    Instant fireC = BatchDateTimeSupport.utcNow().minusSeconds(60);
    TriggerMisfirePendingEntity a = newPending(fireA);
    mapper.insertPending(a);
    TriggerMisfirePendingEntity b = newPending(fireB);
    mapper.insertPending(b);
    TriggerMisfirePendingEntity c = newPending(fireC);
    mapper.insertPending(c);
    mapper.approve(b.getId(), "ops-user");

    List<TriggerMisfirePendingEntity> pending = mapper.selectPendingByTenant(tenantId, 100);
    assertThat(pending)
        .extracting(TriggerMisfirePendingEntity::getId)
        .doesNotContain(b.getId())
        .contains(a.getId(), c.getId());
  }

  @Test
  @DisplayName("审批只对 PENDING 行生效并落库审批人与时间,重复审批因状态已变更返回 0 行")
  void approve_onlyAffectsPendingRows() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);

    int rows = mapper.approve(e.getId(), "ops-user");
    assertThat(rows).isEqualTo(1);

    TriggerMisfirePendingEntity loaded = mapper.selectById(e.getId());
    assertThat(loaded.getStatus()).isEqualTo("APPROVED");
    assertThat(loaded.getApprovedBy()).isEqualTo("ops-user");
    assertThat(loaded.getApprovedAt()).isNotNull();

    // 二次 approve 不再生效(status != PENDING)
    int second = mapper.approve(e.getId(), "ops-user2");
    assertThat(second).isZero();
  }

  @Test
  @DisplayName("驳回只对 PENDING 行生效,写入 REJECTED 状态与驳回原因供运营追溯")
  void reject_onlyAffectsPendingRows() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);

    int rows = mapper.reject(e.getId(), "ops-user", "duplicate launch");
    assertThat(rows).isEqualTo(1);
    TriggerMisfirePendingEntity loaded = mapper.selectById(e.getId());
    assertThat(loaded.getStatus()).isEqualTo("REJECTED");
    assertThat(loaded.getRejectionReason()).isEqualTo("duplicate launch");
  }

  @Test
  @DisplayName("补跑请求落库后回填请求 ID,使待审批记录与真正执行的补跑实例建立关联")
  void linkCatchUpRequest_setsRequestId() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);
    mapper.approve(e.getId(), "ops-user");

    int rows = mapper.linkCatchUpRequest(e.getId(), 999_999L);
    assertThat(rows).isEqualTo(1);
    assertThat(mapper.selectById(e.getId()).getCatchUpRequestId()).isEqualTo(999_999L);
  }

  @Test
  @DisplayName("过期扫描把 expires_at 已超期的 PENDING 记录批量置为 EXPIRED,避免待办长期堆积")
  void markExpired_flipsOverduePendingRows() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);

    // 把 expires_at 改成 1 小时前
    jdbcTemplate.update(
        "update batch.trigger_misfire_pending set expires_at = now() - interval '1 hour' where id ="
            + " ?",
        e.getId());

    int expired = mapper.markExpired(BatchDateTimeSupport.utcNow());
    assertThat(expired).isGreaterThanOrEqualTo(1);
    assertThat(mapper.selectById(e.getId()).getStatus()).isEqualTo("EXPIRED");
  }

  @Test
  @DisplayName("过期扫描只处理 PENDING,已审批记录即使超期也保持 APPROVED 不被误改")
  void markExpired_doesNotTouchAlreadyApproved() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);
    mapper.approve(e.getId(), "ops-user");

    jdbcTemplate.update(
        "update batch.trigger_misfire_pending set expires_at = now() - interval '1 hour' where id ="
            + " ?",
        e.getId());

    mapper.markExpired(BatchDateTimeSupport.utcNow());
    assertThat(mapper.selectById(e.getId()).getStatus()).isEqualTo("APPROVED"); // 不变
  }

  @Test
  @DisplayName("过期待审会原子关闭 ACCEPTED 补跑请求并把 occurrence 标记为 MISFIRE_SKIPPED")
  void expireAndCloseUnlaunched_convergesLinkedRows() {
    Instant fireTime = BatchDateTimeSupport.utcNow().minusSeconds(180);
    TriggerMisfirePendingEntity pending = newPending(fireTime);
    mapper.insertPending(pending);
    long requestRowId = insertCatchUpRequest("expired-request");
    mapper.linkCatchUpRequest(pending.getId(), requestRowId);
    insertOccurrence(fireTime, "MISFIRE_PENDING", "expired-request");
    jdbcTemplate.update(
        "update batch.trigger_misfire_pending set expires_at = now() - interval '1 hour' where id = ?",
        pending.getId());

    int changed = mapper.expireAndCloseUnlaunched(BatchDateTimeSupport.utcNow());

    assertThat(changed).isEqualTo(1);
    assertThat(mapper.selectById(pending.getId()).getStatus()).isEqualTo("EXPIRED");
    assertThat(requestStatus(requestRowId)).isEqualTo("REJECTED");
    assertThat(occurrenceStatus(fireTime)).isEqualTo("MISFIRE_SKIPPED");
    assertThat(mapper.expireAndCloseUnlaunched(BatchDateTimeSupport.utcNow())).isZero();
  }

  @Test
  @DisplayName("统一审批拒绝会以租户和补跑目标精确收敛 pending、请求及 occurrence")
  void rejectAndCloseCentrallyRejected_convergesLinkedRows() {
    Instant fireTime = BatchDateTimeSupport.utcNow().minusSeconds(180);
    TriggerMisfirePendingEntity pending = newPending(fireTime);
    mapper.insertPending(pending);
    long requestRowId = insertCatchUpRequest("rejected-request");
    mapper.linkCatchUpRequest(pending.getId(), requestRowId);
    insertOccurrence(fireTime, "MISFIRE_PENDING", "rejected-request");
    jdbcTemplate.update(
        "insert into batch.approval_command (tenant_id, approval_no, approval_type, action_type,"
            + " target_type, target_id, payload_json, approval_status, approver_id, approval_reason)"
            + " values (?, ?, 'CATCH_UP', 'CATCH_UP', 'CATCH_UP', ?, '{}'::jsonb, 'REJECTED', ?, ?)",
        tenantId,
        "apr-" + System.nanoTime(),
        "rejected-request",
        "ops-user",
        "duplicate launch");

    int changed = mapper.rejectAndCloseCentrallyRejected();

    assertThat(changed).isEqualTo(1);
    TriggerMisfirePendingEntity loaded = mapper.selectById(pending.getId());
    assertThat(loaded.getStatus()).isEqualTo("REJECTED");
    assertThat(loaded.getRejectionReason()).isEqualTo("duplicate launch");
    assertThat(loaded.getApprovedBy()).isEqualTo("ops-user");
    assertThat(requestStatus(requestRowId)).isEqualTo("REJECTED");
    assertThat(occurrenceStatus(fireTime)).isEqualTo("MISFIRE_SKIPPED");
    assertThat(mapper.rejectAndCloseCentrallyRejected()).isZero();
  }

  @Test
  @DisplayName("统一审批批准会同步批准 pending 并把 occurrence 推进到 REQUESTED")
  void approveCentrallyApproved_convergesLinkedRows() {
    Instant fireTime = BatchDateTimeSupport.utcNow().minusSeconds(180);
    TriggerMisfirePendingEntity pending = newPending(fireTime);
    mapper.insertPending(pending);
    long requestRowId = insertCatchUpRequest("approved-request");
    mapper.linkCatchUpRequest(pending.getId(), requestRowId);
    insertOccurrence(fireTime, "MISFIRE_PENDING", "approved-request");
    jdbcTemplate.update(
        "insert into batch.approval_command (tenant_id, approval_no, approval_type, action_type,"
            + " target_type, target_id, payload_json, approval_status, approver_id, approved_at)"
            + " values (?, ?, 'CATCH_UP', 'CATCH_UP', 'CATCH_UP', ?, '{}'::jsonb, 'APPROVED', ?, now())",
        tenantId,
        "apr-" + System.nanoTime(),
        "approved-request",
        "ops-user");

    int changed = mapper.approveCentrallyApproved();

    assertThat(changed).isEqualTo(1);
    TriggerMisfirePendingEntity loaded = mapper.selectById(pending.getId());
    assertThat(loaded.getStatus()).isEqualTo("APPROVED");
    assertThat(loaded.getApprovedBy()).isEqualTo("ops-user");
    assertThat(occurrenceStatus(fireTime)).isEqualTo("REQUESTED");
    assertThat(requestStatus(requestRowId)).isEqualTo("ACCEPTED");
    assertThat(mapper.approveCentrallyApproved()).isZero();
  }

  // ── helpers ─────────────────────────────────────────────

  private TriggerMisfirePendingEntity newPending(Instant scheduledFireTime) {
    TriggerMisfirePendingEntity e = new TriggerMisfirePendingEntity();
    e.setTenantId(tenantId);
    e.setWorkflowDefinitionId(workflowDefinitionId);
    e.setScheduledFireTime(scheduledFireTime);
    e.setExpiresAt(BatchDateTimeSupport.utcNow().plus(Duration.ofDays(7)));
    return e;
  }

  private long insertCatchUpRequest(String requestId) {
    return jdbcTemplate.queryForObject(
        "insert into batch.trigger_request (tenant_id, request_id, trigger_type,"
            + " workflow_definition_id, dedup_key, request_status)"
            + " values (?, ?, 'CATCH_UP', ?, ?, 'ACCEPTED') returning id",
        Long.class,
        tenantId,
        requestId,
        workflowDefinitionId,
        "dedup-" + requestId);
  }

  private void insertOccurrence(Instant fireTime, String status, String requestId) {
    jdbcTemplate.update(
        "insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,"
            + " schedule_version, scheduled_fire_time, occurrence_status, request_id)"
            + " values (?, ?, ?, 1, ?, ?, ?)",
        tenantId,
        workflowDefinitionId,
        workflowCode,
        Timestamp.from(fireTime),
        status,
        requestId);
  }

  private String requestStatus(long requestRowId) {
    return jdbcTemplate.queryForObject(
        "select request_status from batch.trigger_request where id = ?",
        String.class,
        requestRowId);
  }

  private String occurrenceStatus(Instant fireTime) {
    return jdbcTemplate.queryForObject(
        "select occurrence_status from batch.scheduled_occurrence where tenant_id = ?"
            + " and workflow_definition_id = ? and scheduled_fire_time = ?",
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }
}
