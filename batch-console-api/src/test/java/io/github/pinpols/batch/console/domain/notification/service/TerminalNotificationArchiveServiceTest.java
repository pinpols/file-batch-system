package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.mapper.TerminalNotificationArchiveMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.TerminalNotificationArchiveMapper.Family;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationArchiveCandidate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("通知归档单批服务：顺序、停止与失败关闭")
class TerminalNotificationArchiveServiceTest {
  private static final TerminalNotificationArchiveCandidate CANDIDATE =
      new TerminalNotificationArchiveCandidate("t1", "t1:workflow:7:terminal");

  @Mock
  private TerminalNotificationArchiveMapper archive;

  @Mock
  private WorkflowTerminalNotificationMapper receipts;

  @Mock
  private PlatformTransactionManager transactions;

  private TerminalNotificationArchiveService service;

  @BeforeEach
  void setUp() {
    service = new TerminalNotificationArchiveService(
        archive, receipts, new WebhookRelayProperties(), transactions);
  }

  @Test
  @DisplayName("停止时不扫描候选或开启事务")
  void shouldStopWithoutReading_whenShutdownHasStarted() {
    int moved = service.poll(() -> true);
    assertThat(moved).isZero();
    verifyNoInteractions(archive, receipts, transactions);
  }

  @Test
  @DisplayName("未知租户查询结果按异常关闭，不当作无候选归档")
  void shouldFailClosed_whenTenantQueryReturnsUnknownResult() {
    when(archive.enabledTenants("", 50)).thenReturn(null);

    assertThatThrownBy(() -> service.poll(() -> false)).isInstanceOf(NullPointerException.class);

    verify(archive, never()).candidates(any(), any(Integer.class));
    verify(archive, never()).copyFamily(any(), any());
    verify(archive, never()).deleteFamily(any(), any());
    verifyNoInteractions(receipts, transactions);
  }

  @Test
  @DisplayName("候选重新检查不再闭合时不复制或删除")
  void shouldRecheckAfterLock_whenCandidateBecameIneligible() {
    prepareCandidate(null);
    int moved = service.poll(() -> false);
    assertThat(moved).isZero();
    var order = inOrder(receipts, archive);
    order.verify(archive).candidates("t1", 50);
    order.verify(receipts).bindTenant("t1");
    order.verify(receipts).lockReceipt("t1", CANDIDATE.eventKey());
    order.verify(archive).lockReadyReceipt(CANDIDATE);
    verify(archive, never()).copyFamily(any(), any());
    verify(archive, never()).deleteFamily(any(), any());
  }

  @Test
  @DisplayName("仅归档策略复制全部证据但保留热表")
  void shouldRespectArchiveWithoutCleanup_whenCleanupIsDisabled() {
    prepareCandidate(false);
    int moved = service.poll(() -> false);
    assertThat(moved).isEqualTo(1);
    for (Family family : Family.values()) {
      verify(archive).copyFamily(CANDIDATE, family);
      verify(archive).mismatches(CANDIDATE, family);
    }
    verify(archive, never()).deleteFamily(any(), any());
  }

  @Test
  @DisplayName("完整校验全部冷副本后按子表到回执顺序删除")
  void shouldVerifyBeforeDeletingInForeignKeyOrder_whenCleanupIsEnabled() {
    prepareCandidate(true);
    int moved = service.poll(() -> false);
    assertThat(moved).isEqualTo(1);
    var order = inOrder(archive);
    for (Family family : Family.values()) {
      order.verify(archive).copyFamily(CANDIDATE, family);
      order.verify(archive).mismatches(CANDIDATE, family);
    }
    for (Family family :
        List.of(Family.AUDIT, Family.PUSH, Family.RULE, Family.WEBHOOK, Family.RECEIPT)) {
      order.verify(archive).deleteFamily(CANDIDATE, family);
    }
    verify(transactions, times(2)).commit(any());
  }

  @Test
  @DisplayName("任一冷副本冲突导致整族事务回滚且不删热记录")
  void shouldRollbackConflictingCopy_whenColdEvidenceDiffers() {
    prepareCandidate(true);
    when(archive.mismatches(eq(CANDIDATE), any()))
        .thenAnswer(call -> call.getArgument(1) == Family.RULE ? 1L : 0L);
    assertThatThrownBy(() -> service.poll(() -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RULE");
    verify(archive, never()).copyFamily(eq(CANDIDATE), eq(Family.AUDIT));
    verify(archive, never()).deleteFamily(any(), any());
    verify(transactions).rollback(any());
  }

  private void prepareCandidate(Boolean cleanup) {
    when(archive.enabledTenants("", 50)).thenReturn(List.of("t1"));
    when(archive.candidates("t1", 50)).thenReturn(List.of(CANDIDATE));
    when(archive.lockReadyReceipt(CANDIDATE)).thenReturn(cleanup);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }
}
