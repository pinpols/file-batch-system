package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
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

  @BeforeEach
  void seed() {
    tenantId = "mfp-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on"
            + " conflict do nothing",
        tenantId,
        tenantId);
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

  // ── helpers ─────────────────────────────────────────────

  private TriggerMisfirePendingEntity newPending(Instant scheduledFireTime) {
    TriggerMisfirePendingEntity e = new TriggerMisfirePendingEntity();
    e.setTenantId(tenantId);
    e.setJobCode(jobCode);
    e.setScheduledFireTime(scheduledFireTime);
    e.setExpiresAt(BatchDateTimeSupport.utcNow().plus(Duration.ofDays(7)));
    return e;
  }
}
