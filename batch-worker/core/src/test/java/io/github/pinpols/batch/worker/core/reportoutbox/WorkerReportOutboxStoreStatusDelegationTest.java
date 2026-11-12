package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgGiveUpRowParam;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgUpdateGiveUpParam;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgUpdateRetryParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteClaimParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteGiveUpRowParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteUpdateRetryParam;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 存储委托契约：仓储按方言把状态值交给 Mapper 时，必须统一使用 {@link OutboxPublishStatus}，
 * 不能在调用点重新拼字面量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上报发件箱存储委托：状态值统一来自 OutboxPublishStatus")
class WorkerReportOutboxStoreStatusDelegationTest {

  @Mock
  private WorkerReportOutboxPgMapper pgMapper;

  @Mock
  private WorkerReportOutboxSqliteMapper sqliteMapper;

  @Mock
  private JdbcTemplate sqliteJdbc;

  private WorkerReportOutboxRepository postgresRepository() {
    return new WorkerReportOutboxRepository(
        new WorkerReportOutboxProperties(),
        WorkerReportOutboxDialect.POSTGRESQL,
        pgMapper,
        null,
        null);
  }

  private WorkerReportOutboxRepository sqliteRepository() {
    stubSqliteSchema();
    return new WorkerReportOutboxRepository(
        new WorkerReportOutboxProperties(),
        WorkerReportOutboxDialect.SQLITE,
        null,
        sqliteMapper,
        sqliteJdbc);
  }

  /** 构造期会执行建表和兼容性校验；这里按当前 schema 返回最小值，避免触达真实的本地库文件。 */
  @SuppressWarnings("unchecked")
  private void stubSqliteSchema() {
    when(sqliteJdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
    when(sqliteJdbc.queryForList(anyString(), eq(String.class)))
        .thenReturn(List.of("uk_worker_report_outbox_identity"));
    when(sqliteJdbc.queryForList(anyString(), eq(String.class), any()))
        .thenReturn(List.of("tenant_id", "task_id", "partition_invocation_id"));
  }

  @Test
  @DisplayName("PostgreSQL 领取时 NEW 与 PUBLISHING 分别取自枚举编码")
  void shouldUseEnumCodes_whenPgClaimingNext() {
    when(pgMapper.claimNextReturning(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(List.of());

    Optional<WorkerReportOutboxRow> claimed = postgresRepository().claimNext(1000L);

    assertThat(claimed).isEmpty();
    ArgumentCaptor<String> statuses = ArgumentCaptor.forClass(String.class);
    verify(pgMapper)
        .claimNextReturning(anyLong(), statuses.capture(), statuses.capture(), anyString());
    assertThat(statuses.getAllValues())
        .containsExactly(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.PUBLISHING.code());
  }

  @Test
  @DisplayName("PostgreSQL 重置僵死投递、统计、删除与放弃均使用枚举编码")
  void shouldUseEnumCodes_whenPgRunningMaintenanceCalls() {
    WorkerReportOutboxRepository repository = postgresRepository();
    WorkerReportOutboxRow row = new WorkerReportOutboxRow(7L, "{}", "claim-7");
    when(pgMapper.resetStalePublishing(anyString(), anyLong(), anyString(), anyLong()))
        .thenReturn(2);
    when(pgMapper.countByStatus(anyString())).thenReturn(1L);
    when(pgMapper.countStalePublishing(anyString(), anyLong())).thenReturn(3L);
    when(pgMapper.deleteById(anyLong(), anyString(), anyString())).thenReturn(1);
    when(pgMapper.giveUpRow(any(WorkerReportOutboxPgGiveUpRowParam.class))).thenReturn(1);

    assertThat(repository.resetStalePublishing(500L)).isEqualTo(2);
    WorkerReportOutboxStats stats = repository.stats(900L);
    repository.delete(row);
    assertThat(repository.markGiveUp(row, "corrupt payload")).isTrue();

    verify(pgMapper)
        .resetStalePublishing(
            eq(OutboxPublishStatus.NEW.code()),
            anyLong(),
            eq(OutboxPublishStatus.PUBLISHING.code()),
            eq(500L));
    verify(pgMapper).countByStatus(OutboxPublishStatus.NEW.code());
    verify(pgMapper).countByStatus(OutboxPublishStatus.PUBLISHING.code());
    verify(pgMapper).countByStatus(OutboxPublishStatus.GIVE_UP.code());
    verify(pgMapper).countStalePublishing(OutboxPublishStatus.PUBLISHING.code(), 900L);
    verify(pgMapper).deleteById(7L, "claim-7", OutboxPublishStatus.PUBLISHING.code());
    ArgumentCaptor<WorkerReportOutboxPgGiveUpRowParam> giveUp =
        ArgumentCaptor.forClass(WorkerReportOutboxPgGiveUpRowParam.class);
    verify(pgMapper).giveUpRow(giveUp.capture());
    assertThat(giveUp.getValue().publishStatus()).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
    assertThat(giveUp.getValue().statusPublishing())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());
    assertThat(stats.giveUpCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("PostgreSQL 失败累计未达上限走重试、达到上限走放弃")
  void shouldRetryOrGiveUp_whenPgFailureBudgetChanges() {
    WorkerReportOutboxRepository repository = postgresRepository();
    WorkerReportOutboxRow row = new WorkerReportOutboxRow(11L, "{}", "claim-11");
    when(pgMapper.selectAttemptCount(anyLong(), anyString(), anyString())).thenReturn(1);
    when(pgMapper.updateRetry(any(WorkerReportOutboxPgUpdateRetryParam.class))).thenReturn(1);
    RuntimeException cause = new IllegalStateException("transport down");

    assertThat(repository.recordFailure(row, 1000L, cause)).isFalse();

    ArgumentCaptor<WorkerReportOutboxPgUpdateRetryParam> retry =
        ArgumentCaptor.forClass(WorkerReportOutboxPgUpdateRetryParam.class);
    verify(pgMapper).updateRetry(retry.capture());
    assertThat(retry.getValue().publishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(retry.getValue().statusPublishing())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());

    when(pgMapper.selectAttemptCount(anyLong(), anyString(), anyString())).thenReturn(47);
    when(pgMapper.updateGiveUp(any(WorkerReportOutboxPgUpdateGiveUpParam.class)))
        .thenReturn(1);

    assertThat(repository.recordFailure(row, 2000L, cause)).isTrue();

    ArgumentCaptor<WorkerReportOutboxPgUpdateGiveUpParam> giveUp =
        ArgumentCaptor.forClass(WorkerReportOutboxPgUpdateGiveUpParam.class);
    verify(pgMapper).updateGiveUp(giveUp.capture());
    assertThat(giveUp.getValue().publishStatus()).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
    assertThat(giveUp.getValue().statusPublishing())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());
    verify(pgMapper, org.mockito.Mockito.atLeastOnce())
        .selectAttemptCount(11L, "claim-11", OutboxPublishStatus.PUBLISHING.code());
  }

  @Test
  @DisplayName("SQLite 领取时先按 NEW 取候选，再以 PUBLISHING 抢占")
  void shouldUseEnumCodes_whenSqliteClaimingNext() {
    when(sqliteMapper.pickNextNewId(anyLong(), anyString())).thenReturn(5L);
    when(sqliteMapper.updateClaimReturning(any(WorkerReportOutboxSqliteClaimParam.class)))
        .thenReturn(new WorkerReportOutboxRow(5L, "{}", "claim-5"));

    WorkerReportOutboxRepository repository = sqliteRepository();
    Optional<WorkerReportOutboxRow> claimed = repository.claimNext(1000L);

    assertThat(claimed).map(WorkerReportOutboxRow::id).contains(5L);
    verify(sqliteMapper).pickNextNewId(1000L, OutboxPublishStatus.NEW.code());
    ArgumentCaptor<WorkerReportOutboxSqliteClaimParam> claim =
        ArgumentCaptor.forClass(WorkerReportOutboxSqliteClaimParam.class);
    verify(sqliteMapper).updateClaimReturning(claim.capture());
    assertThat(claim.getValue().statusNew()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(claim.getValue().statusPublishing())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());
  }

  @Test
  @DisplayName("SQLite 维护、删除与放弃使用枚举编码")
  void shouldUseEnumCodes_whenSqliteRunningMaintenanceCalls() {
    WorkerReportOutboxRow row = new WorkerReportOutboxRow(9L, "{}", "claim-9");
    when(sqliteMapper.resetStalePublishing(anyString(), anyLong(), anyString(), anyLong()))
        .thenReturn(1);
    when(sqliteMapper.countByStatus(anyString())).thenReturn(0L);
    when(sqliteMapper.countStalePublishing(anyString(), anyLong())).thenReturn(0L);
    when(sqliteMapper.deleteById(anyLong(), anyString(), anyString())).thenReturn(1);
    when(sqliteMapper.giveUpRow(any(WorkerReportOutboxSqliteGiveUpRowParam.class)))
        .thenReturn(1);

    WorkerReportOutboxRepository repository = sqliteRepository();
    repository.resetStalePublishing(500L);
    repository.stats(900L);
    repository.delete(row);
    assertThat(repository.markGiveUp(row, "corrupt payload")).isTrue();

    verify(sqliteMapper)
        .resetStalePublishing(
            eq(OutboxPublishStatus.NEW.code()),
            anyLong(),
            eq(OutboxPublishStatus.PUBLISHING.code()),
            eq(500L));
    verify(sqliteMapper).countByStatus(OutboxPublishStatus.GIVE_UP.code());
    verify(sqliteMapper).deleteById(9L, "claim-9", OutboxPublishStatus.PUBLISHING.code());
    ArgumentCaptor<WorkerReportOutboxSqliteGiveUpRowParam> giveUp =
        ArgumentCaptor.forClass(WorkerReportOutboxSqliteGiveUpRowParam.class);
    verify(sqliteMapper).giveUpRow(giveUp.capture());
    assertThat(giveUp.getValue().publishStatus()).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
    assertThat(giveUp.getValue().statusPublishing())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());
  }

  @Test
  @DisplayName("SQLite 失败累计未达上限走重试")
  void shouldRetry_whenSqliteFailureBudgetRemains() {
    WorkerReportOutboxRow row = new WorkerReportOutboxRow(12L, "{}", "claim-12");
    when(sqliteMapper.selectAttemptCount(anyLong(), anyString(), anyString())).thenReturn(1);
    when(sqliteMapper.updateRetry(any(WorkerReportOutboxSqliteUpdateRetryParam.class)))
        .thenReturn(1);

    WorkerReportOutboxRepository repository = sqliteRepository();
    assertThat(repository.recordFailure(row, 1000L, new IllegalStateException("down")))
        .isFalse();

    ArgumentCaptor<WorkerReportOutboxSqliteUpdateRetryParam> retry =
        ArgumentCaptor.forClass(WorkerReportOutboxSqliteUpdateRetryParam.class);
    verify(sqliteMapper).updateRetry(retry.capture());
    assertThat(retry.getValue().publishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(retry.getValue().statusPublishing())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());
  }
}
