package com.example.batch.console.support.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.console.config.ConsolePushProperties;
import com.example.batch.console.domain.entity.ConsolePushJobNotificationEntity;
import com.example.batch.console.mapper.ConsolePushJobNotificationMapper;
import com.example.batch.console.support.push.ConsolePushSender.PushPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ConsolePushJobNotifier 单测:覆盖跳过/幂等/状态映射/payload 拼装。 */
@ExtendWith(MockitoExtension.class)
class ConsolePushJobNotifierTest {

  @Mock private ConsolePushJobNotificationMapper notificationMapper;
  @Mock private ConsolePushSender pushSender;

  private ConsolePushProperties properties;
  private ConsolePushJobNotifier notifier;

  @BeforeEach
  void setUp() {
    properties = new ConsolePushProperties();
    properties.setEnabled(true);
    properties.getJobNotify().setEnabled(true);
    properties.getJobNotify().setBatchSize(50);
    properties.getJobNotify().setLookbackMinutes(10);
    notifier = new ConsolePushJobNotifier(properties, notificationMapper, pushSender);
  }

  @Test
  void shouldNoopWhenNoPending() {
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of());

    notifier.pollOnce();

    verify(notificationMapper, never()).insertIgnore(any());
    verifyNoInteractions(pushSender);
  }

  @Test
  void shouldSendOncePerPendingAndInsertDedup() {
    PendingJobNotification p = pending(101L, "ta", "alice", "ETL_DAILY", "SUCCESS");
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(1);

    notifier.pollOnce();

    ArgumentCaptor<ConsolePushJobNotificationEntity> dedup =
        ArgumentCaptor.forClass(ConsolePushJobNotificationEntity.class);
    verify(notificationMapper).insertIgnore(dedup.capture());
    assertThat(dedup.getValue().getTenantId()).isEqualTo("ta");
    assertThat(dedup.getValue().getJobInstanceId()).isEqualTo(101L);

    ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
    verify(pushSender).sendToUser(eq("ta"), eq("alice"), payload.capture());
    assertThat(payload.getValue().title()).isEqualTo("任务 ETL_DAILY 已成功");
    assertThat(payload.getValue().body()).isEqualTo("tenant=ta · instance #101");
    assertThat(payload.getValue().tag()).isEqualTo("job-instance-101");
    assertThat(payload.getValue().url()).isEqualTo("/m/jobs/101");
  }

  @Test
  void shouldSkipPushWhenInsertConflicts() {
    PendingJobNotification p = pending(202L, "tb", "bob", "EXPORT", "FAILED");
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(0); // 其它 replica 已抢占

    notifier.pollOnce();

    verify(notificationMapper).insertIgnore(any());
    verifyNoInteractions(pushSender);
  }

  @Test
  void shouldOnlySendForSuccessfullyInsertedAmongMany() {
    PendingJobNotification p1 = pending(301L, "ta", "alice", "J1", "SUCCESS");
    PendingJobNotification p2 = pending(302L, "ta", "alice", "J2", "FAILED");
    PendingJobNotification p3 = pending(303L, "ta", "alice", "J3", "CANCELLED");
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p1, p2, p3));
    when(notificationMapper.insertIgnore(any())).thenReturn(1, 0, 1); // p2 冲突

    notifier.pollOnce();

    verify(notificationMapper, times(3)).insertIgnore(any());
    verify(pushSender, times(2)).sendToUser(any(), any(), any());
  }

  @Test
  void pollSafelyShouldSwallowRuntimeExceptionsSoSchedulerSurvives() {
    when(notificationMapper.findPending(10, 50))
        .thenThrow(new RuntimeException("transient DB error"));

    // 不应抛出 — 否则下次 fixedDelay 不会再触发,poller 永久死掉
    notifier.pollSafely();

    verifyNoInteractions(pushSender);
  }

  @Test
  void shouldMapStatusToZhLabel() {
    assertPayloadTitleForStatus("SUCCESS", "已成功");
    assertPayloadTitleForStatus("FAILED", "已失败");
    assertPayloadTitleForStatus("PARTIAL_FAILED", "部分失败");
    assertPayloadTitleForStatus("CANCELLED", "已取消");
    assertPayloadTitleForStatus("TERMINATED", "已终止");
  }

  private void assertPayloadTitleForStatus(String status, String expectedSuffix) {
    PendingJobNotification p = pending(900L, "ta", "alice", "JC", status);
    when(notificationMapper.findPending(10, 50)).thenReturn(List.of(p));
    when(notificationMapper.insertIgnore(any())).thenReturn(1);

    notifier.pollOnce();

    ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
    verify(pushSender).sendToUser(any(), any(), payload.capture());
    assertThat(payload.getValue().title()).endsWith(expectedSuffix);
    org.mockito.Mockito.reset(pushSender, notificationMapper);
  }

  private static PendingJobNotification pending(
      Long id, String tenant, String operator, String jobCode, String status) {
    PendingJobNotification p = new PendingJobNotification();
    p.setJobInstanceId(id);
    p.setTenantId(tenant);
    p.setOperatorId(operator);
    p.setJobCode(jobCode);
    p.setInstanceStatus(status);
    p.setFinishedAt(Instant.now());
    return p;
  }

  private static <T> T eq(T value) {
    return org.mockito.ArgumentMatchers.eq(value);
  }
}
