package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.page.CursorCodec;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleAiConversationServiceTest {

  @Mock
  private ConsoleAiConversationMapper mapper;

  @Mock
  private BatchObjectCryptoService cryptoService;

  private ConsoleAiProperties properties;
  private ConsoleAiConversationService service;

  @BeforeEach
  void setUp() {
    properties = new ConsoleAiProperties();
    properties.getPersistence().setEnabled(true);
    properties.getPersistence().setRetentionDays(30);
    service = new ConsoleAiConversationService(mapper, properties, cryptoService);
  }

  @Test
  void shouldCreateConversationAndAllocateTurnForCurrentOwner() {
    when(cryptoService.encrypt(org.mockito.ArgumentMatchers.any(byte[].class), isNull()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.selectForUpdate(eq("tenant-a"), anyString())).thenReturn(null);
    when(mapper.selectRecentCompleteTurns(eq("tenant-a"), anyString(), eq(12)))
        .thenReturn(List.of());
    when(mapper.allocateTurnNo(
            eq("tenant-a"), anyString(), anyString(), any(OffsetDateTime.class), anyString()))
        .thenReturn(1L);

    ConsoleAiConversationService.StartedTurn turn =
        service.beginTurn("tenant-a", "operator-a", null, "v1", "show failed jobs");

    assertThat(UUID.fromString(turn.conversationId())).isNotNull();
    assertThat(turn.turnNo()).isEqualTo(1L);
    verify(mapper).setTenantContext("tenant-a");
    verify(mapper).insertConversation(any(ConsoleAiConversationEntity.class));
    verify(mapper).insertTurn(any());
  }

  @Test
  void shouldDenyConversationOwnedByAnotherUser() {
    ConsoleAiConversationEntity conversation = new ConsoleAiConversationEntity();
    conversation.setOwnerUserId("operator-b");
    when(mapper.selectForUpdate("tenant-a", "conversation-1")).thenReturn(conversation);

    assertThatThrownBy(() ->
            service.beginTurn("tenant-a", "operator-a", "conversation-1", "v1", "show failed jobs"))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(mapper, never()).insertTurn(any());
  }

  @Test
  void shouldHideConversationWhenTurnAllocationLosesOwnership() {
    ConsoleAiConversationEntity conversation = new ConsoleAiConversationEntity();
    conversation.setOwnerUserId("operator-a");
    conversation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
    when(mapper.selectForUpdate("tenant-a", "conversation-1")).thenReturn(conversation);
    when(mapper.allocateTurnNo(
            eq("tenant-a"),
            eq("conversation-1"),
            eq("operator-a"),
            any(OffsetDateTime.class),
            eq("v1")))
        .thenReturn(null);

    assertThatThrownBy(() ->
            service.beginTurn("tenant-a", "operator-a", "conversation-1", "v1", "show failed jobs"))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(mapper, never()).insertTurn(any());
  }

  @Test
  void shouldNotRecreateMissingOrExpiredConversation() {
    when(mapper.selectForUpdate("tenant-a", "missing")).thenReturn(null);
    assertThatThrownBy(() -> service.beginTurn("tenant-a", "operator-a", "missing", "v1", "prompt"))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    ConsoleAiConversationEntity expired = new ConsoleAiConversationEntity();
    expired.setOwnerUserId("operator-a");
    expired.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    when(mapper.selectForUpdate("tenant-a", "expired")).thenReturn(expired);
    assertThatThrownBy(() -> service.beginTurn("tenant-a", "operator-a", "expired", "v1", "prompt"))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    verify(mapper, never()).insertConversation(any());
    verify(mapper, never()).insertTurn(any());
  }

  @Test
  void shouldRejectHistoryForMissingOrExpiredConversation() {
    assertThatThrownBy(() -> service.turns("tenant-a", "operator-a", "expired", null, 50))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(mapper, never()).selectTurns(anyString(), anyString(), anyString(), any(), anyInt());
  }

  @Test
  void shouldRejectUnknownContextVersionBeforeDatabaseAccess() {
    assertThatThrownBy(() ->
            service.beginTurn("tenant-a", "operator-a", "conversation-1", "v2", "show failed jobs"))
        .isInstanceOf(BizException.class);
    verify(mapper, never()).setTenantContext(anyString());
  }

  @Test
  void shouldRejectPersistenceWhenDisabled() {
    properties.getPersistence().setEnabled(false);

    assertThatThrownBy(() -> service.list("tenant-a", "operator-a", 10))
        .isInstanceOf(BizException.class);
    verify(mapper, never()).selectByOwner(anyString(), anyString(), anyInt());
  }

  @Test
  void shouldPageConversationsWithStableTimestampAndIdCursor() {
    properties.getPersistence().setConversationPageSize(2);
    Instant updatedAt = Instant.parse("2026-09-30T12:00:00Z");
    ConsoleAiConversationEntity third = conversation("conversation-3", updatedAt);
    ConsoleAiConversationEntity second = conversation("conversation-2", updatedAt);
    ConsoleAiConversationEntity first = conversation("conversation-1", updatedAt);
    when(mapper.selectPageByOwner(eq("tenant-a"), eq("operator-a"), isNull(), isNull(), eq(3)))
        .thenReturn(List.of(third, second, first));
    when(mapper.selectPageByOwner("tenant-a", "operator-a", updatedAt, "conversation-2", 3))
        .thenReturn(List.of(first));

    PageResponse<ConsoleAiConversationService.ConversationView> firstPage =
        service.page("tenant-a", "operator-a", null, 100);
    assertThat(firstPage.items())
        .extracting(ConsoleAiConversationService.ConversationView::id)
        .containsExactly("conversation-3", "conversation-2");
    assertThat(firstPage.pageSize()).isEqualTo(2);
    assertThat(firstPage.hasMore()).isTrue();
    assertThat(CursorCodec.decode(firstPage.nextCursor()))
        .containsAllEntriesOf(Map.of("updatedAt", updatedAt.toString(), "id", "conversation-2"));

    PageResponse<ConsoleAiConversationService.ConversationView> secondPage =
        service.page("tenant-a", "operator-a", firstPage.nextCursor(), 100);
    assertThat(secondPage.items())
        .extracting(ConsoleAiConversationService.ConversationView::id)
        .containsExactly("conversation-1");
    assertThat(secondPage.hasMore()).isFalse();
    assertThat(secondPage.nextCursor()).isNull();
  }

  @Test
  void shouldRejectInvalidPageCursorBeforeDatabaseAccess() {
    assertThatThrownBy(() -> service.page("tenant-a", "operator-a", "not-a-cursor", 20))
        .isInstanceOf(BizException.class);
    verify(mapper, never())
        .selectPageByOwner(anyString(), anyString(), any(), anyString(), anyInt());
  }

  private static ConsoleAiConversationEntity conversation(String id, Instant updatedAt) {
    ConsoleAiConversationEntity row = new ConsoleAiConversationEntity();
    row.setId(id);
    row.setTitle(id);
    row.setContextVersion("v1");
    row.setCreatedAt(updatedAt);
    row.setUpdatedAt(updatedAt);
    row.setExpiresAt(updatedAt.atOffset(ZoneOffset.UTC).plusDays(30));
    return row;
  }
}
