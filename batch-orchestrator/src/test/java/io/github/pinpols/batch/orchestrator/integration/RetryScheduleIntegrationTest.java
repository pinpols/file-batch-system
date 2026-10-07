package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.domain.query.RetryScheduleQuery;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 集成测试：RetryScheduleMapper 在真实数据库上的持久化和查询。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("重试计划持久化与状态推进,校验真实数据库上的到期查询,乐观锁与租户隔离")
class RetryScheduleIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RetryScheduleMapper retryScheduleMapper;

  @Test
  @DisplayName("插入重试计划后可回查,主键生成且租户与关联标识及初始状态与写入一致")
  void shouldInsertAndSelectRetrySchedule() {
    RetryScheduleEntity entity = waitingRetry("t1", 100L, "FIXED", 1, 3);
    retryScheduleMapper.insert(entity);

    assertThat(entity.getId()).isNotNull();

    RetryScheduleEntity loaded = retryScheduleMapper.selectById(entity.getId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getTenantId()).isEqualTo("t1");
    assertThat(loaded.getRelatedId()).isEqualTo(100L);
    assertThat(loaded.getRetryStatus()).isEqualTo(RetryScheduleStatus.WAITING.code());
  }

  @Test
  @DisplayName("按条件查询能把已到重试时间的等待记录查出,结果包含本次插入的记录")
  void shouldFindDueRetrySchedulesViaSelectByQuery() {
    String dedupKey = "t1:due-test:" + BatchDateTimeSupport.utcEpochMillis();
    RetryScheduleEntity entity = waitingRetry("t1", 200L, "FIXED", 1, 3);
    entity.setDedupKey(dedupKey);
    entity.setNextRetryAt(BatchDateTimeSupport.utcNow().minusSeconds(60)); // already due
    retryScheduleMapper.insert(entity);

    List<RetryScheduleEntity> due = retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
        "t1", RetryScheduleStatus.WAITING.code(), BatchDateTimeSupport.utcNow(), 100));

    assertThat(due).isNotEmpty();
    boolean found = due.stream().anyMatch(r -> dedupKey.equals(r.getDedupKey()));
    assertThat(found).isTrue();
  }

  @Test
  @DisplayName("重试时间尚未到达的记录不会被当作到期记录返回")
  void shouldNotReturnFutureSchedulesAsDue() {
    String dedupKey = "t1:future-test:" + BatchDateTimeSupport.utcEpochMillis();
    RetryScheduleEntity entity = waitingRetry("t1", 300L, "FIXED", 1, 3);
    entity.setDedupKey(dedupKey);
    entity.setNextRetryAt(BatchDateTimeSupport.utcNow().plusSeconds(3600)); // not yet due
    retryScheduleMapper.insert(entity);

    List<RetryScheduleEntity> due = retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
        "t1", RetryScheduleStatus.WAITING.code(), BatchDateTimeSupport.utcNow(), 100));

    boolean found = due.stream().anyMatch(r -> dedupKey.equals(r.getDedupKey()));
    assertThat(found).isFalse();
  }

  @Test
  @DisplayName("等待状态的重试计划可被标记为运行中,更新行数为一条")
  void shouldMarkRetryScheduleAsRunning() {
    RetryScheduleEntity entity = waitingRetry("t1", 400L, "FIXED", 2, 3);
    entity.setDedupKey("t1:mark-running:" + BatchDateTimeSupport.utcEpochMillis());
    entity.setNextRetryAt(BatchDateTimeSupport.utcNow().minusSeconds(10));
    retryScheduleMapper.insert(entity);

    int updated = retryScheduleMapper.markRunning(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());

    assertThat(updated).isEqualTo(1);
  }

  @Test
  @DisplayName("同一重试计划第二次标记运行中时不生效,更新行数为零")
  void shouldNotMarkRunningIfStatusAlreadyRunning() {
    RetryScheduleEntity entity = waitingRetry("t1", 500L, "FIXED", 2, 3);
    entity.setDedupKey("t1:no-double-run:" + BatchDateTimeSupport.utcEpochMillis());
    entity.setNextRetryAt(BatchDateTimeSupport.utcNow().minusSeconds(10));
    retryScheduleMapper.insert(entity);

    retryScheduleMapper.markRunning(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());
    // 第二次尝试应失败（通过 fromStatus 检查实现乐观锁）
    int second = retryScheduleMapper.markRunning(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());

    assertThat(second).isZero();
  }

  @Test
  @DisplayName("跨租户标记运行中不生效,记录状态保持等待")
  void shouldNotAdvanceRetryScheduleAcrossTenantBoundary() {
    RetryScheduleEntity entity = waitingRetry("tenant-a", 510L, "FIXED", 1, 3);
    entity.setDedupKey("tenant-a:wrong-tenant:" + BatchDateTimeSupport.utcEpochMillis());
    retryScheduleMapper.insert(entity);

    int updated = retryScheduleMapper.markRunning(
        "tenant-b",
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());

    assertThat(updated).isZero();
    assertThat(retryScheduleMapper.selectById(entity.getId()).getRetryStatus())
        .isEqualTo(RetryScheduleStatus.WAITING.code());
  }

  @Test
  @DisplayName("以过期前置状态标记成功不生效,记录状态保持运行中")
  void shouldNotAdvanceRetryScheduleFromStaleStatus() {
    RetryScheduleEntity entity = waitingRetry("t1", 520L, "FIXED", 1, 3);
    entity.setDedupKey("t1:stale-status:" + BatchDateTimeSupport.utcEpochMillis());
    retryScheduleMapper.insert(entity);

    retryScheduleMapper.markRunning(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());
    int updated = retryScheduleMapper.markSuccess(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.SUCCESS.code());

    assertThat(updated).isZero();
    assertThat(retryScheduleMapper.selectById(entity.getId()).getRetryStatus())
        .isEqualTo(RetryScheduleStatus.RUNNING.code());
  }

  @Test
  @DisplayName("运行中的重试计划可标记成功,更新行数为一条且回查状态为成功")
  void shouldMarkRetryScheduleAsSuccess() {
    RetryScheduleEntity entity = waitingRetry("t1", 600L, "FIXED", 1, 3);
    entity.setDedupKey("t1:mark-success:" + BatchDateTimeSupport.utcEpochMillis());
    entity.setNextRetryAt(BatchDateTimeSupport.utcNow().minusSeconds(10));
    retryScheduleMapper.insert(entity);
    retryScheduleMapper.markRunning(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());

    int updated = retryScheduleMapper.markSuccess(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.RUNNING.code(),
        RetryScheduleStatus.SUCCESS.code());

    assertThat(updated).isEqualTo(1);
    RetryScheduleEntity loaded = retryScheduleMapper.selectById(entity.getId());
    assertThat(loaded.getRetryStatus()).isEqualTo(RetryScheduleStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("运行中的重试计划可标记失败,更新行数为一条且回查状态与错误码与写入一致")
  void shouldMarkRetryScheduleAsFailed() {
    RetryScheduleEntity entity = waitingRetry("t1", 700L, "FIXED", 1, 3);
    entity.setDedupKey("t1:mark-failed:" + BatchDateTimeSupport.utcEpochMillis());
    entity.setNextRetryAt(BatchDateTimeSupport.utcNow().minusSeconds(10));
    retryScheduleMapper.insert(entity);
    retryScheduleMapper.markRunning(
        entity.getTenantId(),
        entity.getId(),
        RetryScheduleStatus.WAITING.code(),
        RetryScheduleStatus.RUNNING.code());

    RetryScheduleMapper.MarkFailedParam markFailedParam =
        RetryScheduleMapper.MarkFailedParam.builder()
            .tenantId(entity.getTenantId())
            .id(entity.getId())
            .fromStatus(RetryScheduleStatus.RUNNING.code())
            .retryStatus(RetryScheduleStatus.FAILED.code())
            .lastErrorCode("DISPATCH_FAILED")
            .lastErrorMessage("connection refused")
            .nextRetryAt(BatchDateTimeSupport.utcNow().plusSeconds(120))
            .build();
    int updated = retryScheduleMapper.markFailed(markFailedParam);

    assertThat(updated).isEqualTo(1);
    RetryScheduleEntity loaded = retryScheduleMapper.selectById(entity.getId());
    assertThat(loaded.getRetryStatus()).isEqualTo(RetryScheduleStatus.FAILED.code());
    assertThat(loaded.getLastErrorCode()).isEqualTo("DISPATCH_FAILED");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static RetryScheduleEntity waitingRetry(
      String tenantId, Long relatedId, String retryPolicy, int retryCount, int maxRetryCount) {
    RetryScheduleEntity e = new RetryScheduleEntity();
    e.setTenantId(tenantId);
    e.setRelatedType("JOB_PARTITION");
    e.setRelatedId(relatedId);
    e.setRetryPolicy(retryPolicy);
    e.setRetryCount(retryCount);
    e.setMaxRetryCount(maxRetryCount);
    e.setNextRetryAt(BatchDateTimeSupport.utcNow().minusSeconds(30));
    e.setRetryStatus(RetryScheduleStatus.WAITING.code());
    e.setDedupKey(tenantId + ":" + relatedId + ":" + retryCount);
    return e;
  }
}
