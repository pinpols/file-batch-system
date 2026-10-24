package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiAttachmentBindParam;
import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiAttachmentMarkDraftParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookDeliveryLogMarkRetryFailureParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookDeliveryLogUpdateGiveUpParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookDeliveryLogUpdateRetryFailureParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiTurnEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper.BindTransitionParam;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper.DraftTransitionParam;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.TerminalNotificationRecoveryMapper;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryReconcileStoppedParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalTransitionParam;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@DisplayName("Mapper 状态默认方法:固定状态参数与调用方参数保持一致")
class MapperStatusDefaultsTest {

  @Test
  @DisplayName("AI 附件状态转换装配完整的 CAS 参数")
  void shouldBuildAttachmentTransitions() {
    ConsoleAiAttachmentMapper mapper = mock(ConsoleAiAttachmentMapper.class, CALLS_REAL_METHODS);
    UUID id = UUID.randomUUID();
    Instant expiresAt = Instant.parse("2026-10-13T00:00:00Z");
    doReturn(1).when(mapper).transitionToDraft(any());
    doReturn(1).when(mapper).transitionToBound(any());

    assertThat(mapper.markDraft(ConsoleAiAttachmentMarkDraftParam.builder()
        .tenantId("tenant-a")
        .id(id)
        .mediaType("image/png")
        .byteSize(128L)
        .width(10)
        .height(20)
        .build())).isEqualTo(1);
    assertThat(mapper.bind(ConsoleAiAttachmentBindParam.builder()
        .tenantId("tenant-a")
        .id(id)
        .ownerUserId("user-a")
        .conversationId("conversation-a")
        .turnNo(7L)
        .expiresAt(expiresAt)
        .build()))
        .isEqualTo(1);

    verify(mapper)
        .transitionToDraft(new DraftTransitionParam(
            "tenant-a", id, "image/png", 128L, 10, 20, "UPLOADING", "DRAFT"));
    verify(mapper)
        .transitionToBound(new BindTransitionParam(
            "tenant-a", id, "user-a", "conversation-a", 7L, expiresAt, "DRAFT", "BOUND"));
  }

  @Test
  @DisplayName("AI 会话查询与写入使用预期的 turn 状态")
  void shouldDelegateConversationStatuses() {
    ConsoleAiConversationMapper mapper =
        mock(ConsoleAiConversationMapper.class, CALLS_REAL_METHODS);
    ConsoleAiTurnEntity turn = mock(ConsoleAiTurnEntity.class);
    List<ConsoleAiTurnEntity> expected = List.of(turn);
    doReturn(expected)
        .when(mapper)
        .selectRecentTurnsByStatuses(
            "tenant-a", "conversation-a", 15, List.of("COMPLETE", "FAILED"));
    doReturn(1).when(mapper).insertTurnWithStatus(turn, "IN_PROGRESS");
    doReturn(1).when(mapper).completeTurnIfCurrent(turn, "IN_PROGRESS");

    assertThat(mapper.selectRecentCompleteTurns("tenant-a", "conversation-a", 15))
        .isSameAs(expected);
    assertThat(mapper.insertTurn(turn)).isEqualTo(1);
    assertThat(mapper.completeTurn(turn)).isEqualTo(1);
  }

  @Test
  @DisplayName("Webhook 重试入口传递候选状态与结果状态")
  void shouldDelegateWebhookStatuses() {
    ConsoleWebhookDeliveryLogMapper mapper =
        mock(ConsoleWebhookDeliveryLogMapper.class, CALLS_REAL_METHODS);
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    List<String> eligibleStatuses = List.of("PENDING", "EXHAUSTED");
    doReturn(List.of()).when(mapper).findEligibleRetriesByStatuses(now, 15, eligibleStatuses);
    doReturn(1).when(mapper).claimForRetryByStatuses(9L, eligibleStatuses);
    doReturn(1).when(mapper).updateRetrySuccess(9L, 2, 204, "SUCCESS");
    doReturn(1).when(mapper).updateRetryFailure(ConsoleWebhookDeliveryLogUpdateRetryFailureParam.builder()
        .id(9L)
        .attempt(2)
        .httpStatus(503)
        .responseBody("down")
        .nextRetryAt(now)
        .deliveryStatus("EXHAUSTED")
        .build());
    doReturn(1).when(mapper).updateGiveUp(ConsoleWebhookDeliveryLogUpdateGiveUpParam.builder()
        .id(9L)
        .attempt(3)
        .httpStatus(503)
        .responseBody("down")
        .deliveryStatus("GIVE_UP")
        .build());

    assertThat(mapper.findEligibleRetries(now, 15)).isEmpty();
    assertThat(mapper.claimForRetry(9L)).isEqualTo(1);
    assertThat(mapper.markRetrySuccess(9L, 2, 204)).isEqualTo(1);
    assertThat(mapper.markRetryFailure(ConsoleWebhookDeliveryLogMarkRetryFailureParam.builder()
        .id(9L)
        .attempt(2)
        .httpStatus(503)
        .responseBody("down")
        .nextRetryAt(now)
        .build())).isEqualTo(1);
    assertThat(mapper.markGiveUp(9L, 3, 503, "down")).isEqualTo(1);
  }

  @Test
  @DisplayName("配置审批转换参数由具名工厂固定待审与目标状态")
  void shouldBuildApprovalTransitionParamsWithFixedStatuses() {
    ConfigApprovalMapper mapper = mock(ConfigApprovalMapper.class);
    doReturn(1).when(mapper).transition(any());
    ConfigApprovalTransitionParam approved =
        ConfigApprovalTransitionParam.approved("tenant-a", 3L, "reviewer", "ok");
    ConfigApprovalTransitionParam rejected =
        ConfigApprovalTransitionParam.rejected("tenant-a", 3L, "reviewer", "not ok");
    ArgumentCaptor<ConfigApprovalTransitionParam> captor = ArgumentCaptor.captor();

    assertThat(mapper.transition(approved)).isEqualTo(1);
    assertThat(mapper.transition(rejected)).isEqualTo(1);
    verify(mapper, Mockito.times(2)).transition(captor.capture());

    assertThat(captor.getAllValues().get(0).getExpectedStatus()).isEqualTo("PENDING");
    assertThat(captor.getAllValues().get(0).getTargetStatus()).isEqualTo("APPROVED");
    assertThat(captor.getAllValues().get(1).getExpectedStatus()).isEqualTo("PENDING");
    assertThat(captor.getAllValues().get(1).getTargetStatus()).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("设备投递归并固定耗尽状态并保留原租户、期限与批次边界")
  void shouldDelegateStoppedPushReconciliationWithFixedStatus() {
    JobPushDeliveryMapper mapper = mock(JobPushDeliveryMapper.class, CALLS_REAL_METHODS);
    Instant now = Instant.parse("2026-10-24T00:00:00Z");
    doReturn(2).when(mapper).reconcileStoppedWithStatus(any());

    int reconciled = mapper.reconcileStopped("tenant-a", now, 4, 25);

    assertThat(reconciled).isEqualTo(2);
    ArgumentCaptor<JobPushDeliveryReconcileStoppedParam> captor = ArgumentCaptor.captor();
    verify(mapper).reconcileStoppedWithStatus(captor.capture());
    JobPushDeliveryReconcileStoppedParam parameters = captor.getValue();
    assertThat(parameters.tenantId()).isEqualTo("tenant-a");
    assertThat(parameters.now()).isSameAs(now);
    assertThat(parameters.maxAttempts()).isEqualTo(4);
    assertThat(parameters.limit()).isEqualTo(25);
    assertThat(parameters.targetStatus()).isEqualTo("EXHAUSTED");
  }

  @Test
  @DisplayName("设备领取的固定目标状态可由 MyBatis 读取，领取参数行形状保持不变")
  void shouldExposeFixedPushClaimStatusToMyBatis() {
    JobPushDelivery row = new JobPushDelivery(
        8L, "tenant-a", "event-a", "operator-a", 9L, "fingerprint-a", "{}", 0);
    Instant now = Instant.parse("2026-10-24T00:00:00Z");
    Instant until = now.plusSeconds(60);
    JobPushDeliveryClaimParam claim = new JobPushDeliveryClaimParam(
        row, "00000000-0000-0000-0000-000000000001", now, until, 4);
    MetaObject bindings = SystemMetaObject.forObject(claim);

    Object targetStatus = bindings.getValue("targetStatus");
    Object boundRow = bindings.getValue("row");
    Object boundNow = bindings.getValue("now");
    Object boundUntil = bindings.getValue("until");
    Object boundMaxAttempts = bindings.getValue("maxAttempts");

    assertThat(targetStatus).isEqualTo("IN_FLIGHT");
    assertThat(boundRow).isSameAs(row);
    assertThat(boundNow).isSameAs(now);
    assertThat(boundUntil).isSameAs(until);
    assertThat(boundMaxAttempts).isEqualTo(4);
  }

  @Test
  @DisplayName("人工重新投递固定回到待处理状态并保留原来源与意图标识")
  void shouldRearmTerminalNotificationWithFixedPendingStatus() {
    TerminalNotificationRecoveryMapper mapper =
        mock(TerminalNotificationRecoveryMapper.class, CALLS_REAL_METHODS);
    doReturn(1).when(mapper).rearmWithStatus("tenant-a", "PUSH", 8L, "PENDING");

    int rearmed = mapper.rearm("tenant-a", "PUSH", 8L);

    assertThat(rearmed).isEqualTo(1);
    verify(mapper).rearmWithStatus("tenant-a", "PUSH", 8L, "PENDING");
  }
}
