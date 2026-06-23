package io.github.pinpols.batch.console.domain.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushApprovalNotificationEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushApprovalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ConsolePushApprovalNotifier 单测:覆盖跳过/幂等/标签映射/payload 拼装。 */
@ExtendWith(MockitoExtension.class)
class ConsolePushApprovalNotifierTest {

  @Mock private ConsolePushApprovalNotificationMapper notificationMapper;
  @Mock private ConsolePushSender pushSender;

  private ConsolePushProperties properties;
  private ConsolePushApprovalNotifier notifier;

  @BeforeEach
  void setUp() {
    properties = new ConsolePushProperties();
    properties.setEnabled(true);
    properties.getApprovalNotify().setEnabled(true);
    properties.getApprovalNotify().setBatchSize(50);
    properties.getApprovalNotify().setLookbackMinutes(10);
    notifier = new ConsolePushApprovalNotifier(properties, notificationMapper, pushSender);
  }

  @Test
  void shouldNoopWhenNoPending() {
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of());

    notifier.pollOnce();

    verify(notificationMapper, never()).insertIgnore(any());
    verifyNoInteractions(pushSender);
  }

  @Test
  void shouldPushApprovedWithReasonToRequester() {
    PendingApprovalNotification p =
        approval("ap-1", "ta", "CATCH_UP", "APPROVED", "alice", "bob", "OK to retry", null);
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(1);

    notifier.pollOnce();

    ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
    verify(pushSender).sendToUser(eq("ta"), eq("alice"), payload.capture());
    assertThat(payload.getValue().title()).isEqualTo("补跑 申请已批准");
    assertThat(payload.getValue().body()).contains("bob").contains("OK to retry");
    assertThat(payload.getValue().tag()).isEqualTo("approval-ap-1");
    assertThat(payload.getValue().url()).isEqualTo("/m/approvals?id=ap-1");
  }

  @Test
  void shouldUseRejectionReasonWhenRejected() {
    PendingApprovalNotification p =
        approval(
            "ap-2", "ta", "COMPENSATION", "REJECTED", "alice", "bob", null, "blocked by policy");
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(1);

    notifier.pollOnce();

    ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
    verify(pushSender).sendToUser(any(), any(), payload.capture());
    assertThat(payload.getValue().title()).isEqualTo("补偿 申请已驳回");
    assertThat(payload.getValue().body()).contains("blocked by policy");
  }

  @Test
  void shouldFallBackBodyWhenReasonBlank() {
    PendingApprovalNotification p =
        approval("ap-3", "ta", "DOWNLOAD", "EXECUTED", "alice", null, "", null);
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(1);

    notifier.pollOnce();

    ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
    verify(pushSender).sendToUser(any(), any(), payload.capture());
    assertThat(payload.getValue().title()).isEqualTo("下载 申请已执行");
    assertThat(payload.getValue().body()).contains("approver=-").contains("ap-3");
  }

  @Test
  void shouldSkipPushWhenInsertConflicts() {
    PendingApprovalNotification p =
        approval("ap-4", "tb", "DLQ_REPLAY", "APPROVED", "carol", "dan", "ok", null);
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(0);

    notifier.pollOnce();

    verify(notificationMapper).insertIgnore(any());
    verifyNoInteractions(pushSender);
  }

  @Test
  void shouldDedupRecordCarriesTenantAndApprovalNo() {
    PendingApprovalNotification p =
        approval("ap-5", "tc", "CATCH_UP", "APPROVED", "alice", "bob", "ok", null);
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(1);

    notifier.pollOnce();

    ArgumentCaptor<ConsolePushApprovalNotificationEntity> rec =
        ArgumentCaptor.forClass(ConsolePushApprovalNotificationEntity.class);
    verify(notificationMapper).insertIgnore(rec.capture());
    assertThat(rec.getValue().getTenantId()).isEqualTo("tc");
    assertThat(rec.getValue().getApprovalNo()).isEqualTo("ap-5");
  }

  @Test
  void pollSafelyShouldSwallowRuntimeExceptionsSoSchedulerSurvives() {
    when(notificationMapper.findPending(10, 50))
        .thenThrow(new RuntimeException("transient DB error"));

    notifier.pollSafely();

    verifyNoInteractions(pushSender);
  }

  @Test
  void shouldProcessMultipleAndSendForEachAccepted() {
    PendingApprovalNotification p1 =
        approval("ap-a", "ta", "CATCH_UP", "APPROVED", "alice", "bob", "ok", null);
    PendingApprovalNotification p2 =
        approval("ap-b", "ta", "DOWNLOAD", "REJECTED", "alice", "bob", null, "no");
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p1, p2));
    when(notificationMapper.insertIgnore(any())).thenReturn(1, 0);

    notifier.pollOnce();

    verify(notificationMapper, times(2)).insertIgnore(any());
    verify(pushSender, times(1)).sendToUser(any(), any(), any());
  }

  private static PendingApprovalNotification approval(
      String no,
      String tenant,
      String type,
      String status,
      String requester,
      String approver,
      String approvalReason,
      String rejectionReason) {
    PendingApprovalNotification p = new PendingApprovalNotification();
    p.setApprovalNo(no);
    p.setTenantId(tenant);
    p.setApprovalType(type);
    p.setApprovalStatus(status);
    p.setRequesterId(requester);
    p.setApproverId(approver);
    p.setApprovalReason(approvalReason);
    p.setRejectionReason(rejectionReason);
    p.setApprovedAt(Instant.now());
    return p;
  }

  private static <T> T eq(T value) {
    return org.mockito.ArgumentMatchers.eq(value);
  }
}
