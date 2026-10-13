package io.github.pinpols.batch.console.mapper;

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
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalTransitionParam;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    assertThat(mapper.markDraft("tenant-a", id, "image/png", 128L, 10, 20)).isEqualTo(1);
    assertThat(mapper.bind("tenant-a", id, "user-a", "conversation-a", 7L, expiresAt))
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
    doReturn(1).when(mapper).updateRetryFailure(9L, 2, 503, "down", now, "EXHAUSTED");
    doReturn(1).when(mapper).updateGiveUp(9L, 3, 503, "down", "GIVE_UP");

    assertThat(mapper.findEligibleRetries(now, 15)).isEmpty();
    assertThat(mapper.claimForRetry(9L)).isEqualTo(1);
    assertThat(mapper.markRetrySuccess(9L, 2, 204)).isEqualTo(1);
    assertThat(mapper.markRetryFailure(9L, 2, 503, "down", now)).isEqualTo(1);
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
    verify(mapper, org.mockito.Mockito.times(2)).transition(captor.capture());

    assertThat(captor.getAllValues().get(0).getExpectedStatus()).isEqualTo("PENDING");
    assertThat(captor.getAllValues().get(0).getTargetStatus()).isEqualTo("APPROVED");
    assertThat(captor.getAllValues().get(1).getExpectedStatus()).isEqualTo("PENDING");
    assertThat(captor.getAllValues().get(1).getTargetStatus()).isEqualTo("REJECTED");
  }
}
