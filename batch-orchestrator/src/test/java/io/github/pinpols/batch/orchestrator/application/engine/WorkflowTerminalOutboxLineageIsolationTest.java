package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.lineage.OpenLineageEmitter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 守护 OpenLineage 血缘 emit 的「热路径隔离」设计契约 —— 血缘是 observability,绝不能牵连 workflow_run 终态 outbox
 * 主链。两条当前未被覆盖的性质:
 *
 * <ul>
 *   <li><b>性质 (1) 失败隔离</b>:即使 emitter 抛异常,终态 outbox 写入(domainEventPublisher.publish)照常完成, 且 emit
 *       严格排在提交之后,失败不会回滚 / 撤销已落库的终态事件。
 *   <li><b>性质 (2) 仅 afterCommit</b>:emit 通过 {@link TransactionSynchronization#afterCommit()}
 *       注册,提交后才 fire;事务回滚(afterCompletion=STATUS_ROLLED_BACK,不触发 afterCommit)时 emit 被跳过,不发假血缘。
 * </ul>
 *
 * <p>生产路径 {@code writeTerminalEvent} 标注 {@code @Transactional(propagation=MANDATORY)},运行时恒有活跃事务
 * 同步,因此走注册 afterCommit 分支;本测试用 {@link TransactionSynchronizationManager#initSynchronization()} 复现
 * 该活跃同步条件,再手动驱动 afterCommit() / afterCompletion() 模拟提交与回滚。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTerminalOutboxLineageIsolationTest {

  @Mock private DomainEventPublisher domainEventPublisher;

  @Mock private OpenLineageEmitter openLineageEmitter;

  private WorkflowTerminalOutboxService service;

  @BeforeEach
  void setUp() {
    service = new WorkflowTerminalOutboxService(domainEventPublisher, openLineageEmitter);
  }

  @AfterEach
  void tearDown() {
    // 复现活跃同步的测试必须清理线程本地状态,避免污染后续测试。
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ===== 性质 (1):emit 失败不破坏终态 outbox 写入,也不回滚已落库事件 =====

  @Test
  @DisplayName("性质(1): emitter 抛异常 → 终态方法正常返回、outbox 已写入,emit 失败只在提交后浮现、不撤销落库")
  void emitFailure_doesNotBreakTerminalWrite_andCannotUndoCommittedOutbox() {
    // arrange: 活跃事务同步(复现 MANDATORY 生产条件)+ emitter 被打成抛异常的最坏情况
    TransactionSynchronizationManager.initSynchronization();
    doThrow(new RuntimeException("emit boom"))
        .when(openLineageEmitter)
        .emitWorkflowTerminal(any(), any(), any());

    WorkflowRunEntity run = workflowRun();
    Instant finished = Instant.parse("2026-05-20T10:00:00Z");

    // act: 终态写入注册 afterCommit + 发布 outbox 事件,即使 emitter 已被打成抛异常也应正常返回
    assertThatCode(
            () -> service.writeTerminalEvent(run, WorkflowRunStatus.SUCCESS.code(), finished))
        .doesNotThrowAnyException();

    // assert: outbox 写入已发生;emit 被推迟到 afterCommit(此刻尚未触发),故其失败不可能影响本次写入
    verify(domainEventPublisher).publish(any(DomainEvent.class));
    verify(openLineageEmitter, never()).emitWorkflowTerminal(any(), any(), any());

    // 失败的 emit 只在 afterCommit 才浮现 —— 严格排在 outbox 提交之后,无法回滚 / 撤销已发布的终态事件。
    // (生产 emitter 在 sendQuietly 内部 swallow 一切异常,此处抛异常的 mock 是人为构造的最坏情况。)
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).hasSize(1);
    assertThatThrownBy(() -> syncs.get(0).afterCommit())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("emit boom");

    // 无论提交后 emit 是否失败,outbox 写入岿然不动(仍恰好一次)。
    verify(domainEventPublisher, times(1)).publish(any(DomainEvent.class));
  }

  // ===== 性质 (2):仅 afterCommit fire;回滚时跳过 =====

  @Test
  @DisplayName("性质(2)-回滚: 事务回滚(afterCompletion=ROLLED_BACK,无 afterCommit) → emitter 从不被调用")
  void lineageEmit_skippedOnRollback() {
    // arrange
    TransactionSynchronizationManager.initSynchronization();

    // act: 终态写入注册同步
    service.writeTerminalEvent(workflowRun(), WorkflowRunStatus.FAILED.code(), Instant.now());
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).hasSize(1);
    // 模拟回滚:走 afterCompletion(STATUS_ROLLED_BACK),永不调用 afterCommit
    syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    // assert: 回滚路径下 emit 被完全跳过,不发假血缘
    verify(openLineageEmitter, never()).emitWorkflowTerminal(any(), any(), any());
  }

  @Test
  @DisplayName("性质(2)-提交: afterCommit 驱动后 emitter 恰好被调用一次(且提交前不被调用)")
  void lineageEmit_firesOnAfterCommitOnly() {
    // arrange
    TransactionSynchronizationManager.initSynchronization();
    WorkflowRunEntity run = workflowRun();
    Instant finished = Instant.parse("2026-05-20T10:00:00Z");

    // act: 终态写入注册同步
    service.writeTerminalEvent(run, WorkflowRunStatus.SUCCESS.code(), finished);

    // assert: 提交前(尚未 afterCommit)不得触发 emit
    verify(openLineageEmitter, never()).emitWorkflowTerminal(any(), any(), any());

    // 驱动 afterCommit(模拟事务成功提交)
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).hasSize(1);
    syncs.get(0).afterCommit();

    // assert: 提交后 emit 恰好一次,参数透传
    verify(openLineageEmitter)
        .emitWorkflowTerminal(run, WorkflowRunStatus.SUCCESS.code(), finished);
  }

  // ===== fixtures =====

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(100L);
    run.setTenantId("ta");
    run.setWorkflowDefinitionId(50L);
    run.setRelatedJobInstanceId(200L);
    run.setTraceId("trace-xx");
    return run;
  }
}
