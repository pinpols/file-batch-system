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
class TriggerMisfirePendingMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TriggerMisfirePendingMapper mapper;
  @Autowired private JdbcTemplate jdbcTemplate;

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
  void insertPendingThenSelectStatus() {
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
  void insertPendingDuplicateThrowsOnUniqueConstraint() {
    Instant scheduled = BatchDateTimeSupport.utcNow().minusSeconds(120);
    mapper.insertPending(newPending(scheduled));

    assertThatThrownBy(() -> mapper.insertPending(newPending(scheduled)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void selectPendingByTenantOnlyReturnsPending() {
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
  void approveOnlyAffectsPendingRows() {
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
  void rejectOnlyAffectsPendingRows() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);

    int rows = mapper.reject(e.getId(), "ops-user", "duplicate launch");
    assertThat(rows).isEqualTo(1);
    TriggerMisfirePendingEntity loaded = mapper.selectById(e.getId());
    assertThat(loaded.getStatus()).isEqualTo("REJECTED");
    assertThat(loaded.getRejectionReason()).isEqualTo("duplicate launch");
  }

  @Test
  void linkCatchUpRequestSetsRequestId() {
    TriggerMisfirePendingEntity e = newPending(BatchDateTimeSupport.utcNow().minusSeconds(60));
    mapper.insertPending(e);
    mapper.approve(e.getId(), "ops-user");

    int rows = mapper.linkCatchUpRequest(e.getId(), 999_999L);
    assertThat(rows).isEqualTo(1);
    assertThat(mapper.selectById(e.getId()).getCatchUpRequestId()).isEqualTo(999_999L);
  }

  @Test
  void markExpiredFlipsOverduePendingRows() {
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
  void markExpiredDoesNotTouchAlreadyApproved() {
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
    return e;
  }
}
