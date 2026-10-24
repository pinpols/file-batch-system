package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiTurnStartInput;

import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiConversationAllocateTurnNoParam;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiConversationPageByOwnerQuery;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiConversationTurnsQuery;


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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 会话服务:会话创建、归属校验、分页与持久化开关")
class ConsoleAiConversationServiceTest {

  @Mock
  private ConsoleAiConversationMapper mapper;

  @Mock
  private BatchObjectCryptoService cryptoService;

  @Mock
  private ConsoleAiAttachmentService attachmentService;

  private ConsoleAiProperties properties;
  private ConsoleAiConversationService service;

  @BeforeEach
  void setUp() {
    properties = new ConsoleAiProperties();
    properties.getPersistence().setEnabled(true);
    properties.getPersistence().setRetentionDays(30);
    service =
        new ConsoleAiConversationService(mapper, properties, cryptoService, attachmentService);
  }

  @Test
  @DisplayName("会话创建:为当前用户新建会话,并分配起始轮次")
  void shouldCreateConversationAndAllocateTurnForCurrentOwner() {
    when(mapper.insertTurn(any())).thenReturn(1);
    when(cryptoService.encrypt(any(byte[].class), isNull()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.selectForUpdate(eq("tenant-a"), anyString())).thenReturn(null);
    when(mapper.selectRecentCompleteTurns(eq("tenant-a"), anyString(), eq(12)))
        .thenReturn(List.of());
    when(mapper.allocateTurnNo(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "tenant-a") && parameters.conversationId() != null && parameters.ownerUserId() != null && parameters.expiresAt() != null && parameters.contextVersion() != null)))
        .thenReturn(1L);

    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId(null)
        .contextVersion("v1")
        .prompt("show failed jobs")
        .build();
    ConsoleAiConversationService.StartedTurn turn =
        service.beginTurn(turnInput);

    assertThat(UUID.fromString(turn.conversationId())).isNotNull();
    assertThat(turn.turnNo()).isEqualTo(1L);
    verify(mapper).setTenantContext("tenant-a");
    verify(mapper).insertConversation(any(ConsoleAiConversationEntity.class));
    verify(mapper).insertTurn(any());
  }

  @Test
  @DisplayName("客户端轮次标识重复:按并发冲突拒绝")
  void shouldRejectAsConflict_whenClientTurnIdDuplicated() {
    when(cryptoService.encrypt(any(byte[].class), isNull()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.selectRecentCompleteTurns(eq("tenant-a"), anyString(), eq(12)))
        .thenReturn(List.of());
    when(mapper.allocateTurnNo(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "tenant-a") && parameters.conversationId() != null && parameters.ownerUserId() != null && parameters.expiresAt() != null && parameters.contextVersion() != null)))
        .thenReturn(1L);
    when(mapper.insertTurn(any())).thenReturn(0);

    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId(null)
        .contextVersion("v1")
        .prompt("question")
        .clientTurnId(UUID.randomUUID())
        .attachmentIds(List.of())
        .build();
    assertThatThrownBy(() -> service.beginTurn(turnInput))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("会话归属他人:按不存在处理,且不写入轮次")
  void shouldDenyConversationOwnedByAnotherUser() {
    ConsoleAiConversationEntity conversation = new ConsoleAiConversationEntity();
    conversation.setOwnerUserId("operator-b");
    when(mapper.selectForUpdate("tenant-a", "conversation-1")).thenReturn(conversation);

    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId("conversation-1")
        .contextVersion("v1")
        .prompt("show failed jobs")
        .build();
    assertThatThrownBy(() ->
            service.beginTurn(turnInput))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(mapper, never()).insertTurn(any());
  }

  @Test
  @DisplayName("轮次分配未命中归属:按不存在处理,且不写入轮次")
  void shouldHideConversationWhenTurnAllocationLosesOwnership() {
    ConsoleAiConversationEntity conversation = new ConsoleAiConversationEntity();
    conversation.setOwnerUserId("operator-a");
    conversation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
    when(mapper.selectForUpdate("tenant-a", "conversation-1")).thenReturn(conversation);
    when(mapper.allocateTurnNo(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "tenant-a") && java.util.Objects.equals(parameters.conversationId(), "conversation-1") && java.util.Objects.equals(parameters.ownerUserId(), "operator-a") && parameters.expiresAt() != null && java.util.Objects.equals(parameters.contextVersion(), "v1"))))
        .thenReturn(null);

    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId("conversation-1")
        .contextVersion("v1")
        .prompt("show failed jobs")
        .build();
    assertThatThrownBy(() ->
            service.beginTurn(turnInput))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(mapper, never()).insertTurn(any());
  }

  @Test
  @DisplayName("会话缺失或已过期:不重建会话,也不写入轮次")
  void shouldNotRecreateMissingOrExpiredConversation() {
    when(mapper.selectForUpdate("tenant-a", "missing")).thenReturn(null);
    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId("missing")
        .contextVersion("v1")
        .prompt("prompt")
        .build();
    assertThatThrownBy(() -> service.beginTurn(turnInput))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    ConsoleAiConversationEntity expired = new ConsoleAiConversationEntity();
    expired.setOwnerUserId("operator-a");
    expired.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    when(mapper.selectForUpdate("tenant-a", "expired")).thenReturn(expired);
    ConsoleAiTurnStartInput expiredTurnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId("expired")
        .contextVersion("v1")
        .prompt("prompt")
        .build();
    assertThatThrownBy(() -> service.beginTurn(expiredTurnInput))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    verify(mapper, never()).insertConversation(any());
    verify(mapper, never()).insertTurn(any());
  }

  @Test
  @DisplayName("会话缺失或已过期:拒绝查询历史,且不访问明细查询")
  void shouldRejectHistoryForMissingOrExpiredConversation() {
    assertThatThrownBy(() -> service.turns("tenant-a", "operator-a", "expired", null, 50))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(mapper, never()).selectTurns(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.conversationId() != null && parameters.ownerUserId() != null));
  }

  @Test
  @DisplayName("上下文版本未知:在访问数据库之前即拒绝")
  void shouldRejectUnknownContextVersionBeforeDatabaseAccess() {
    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .requestedConversationId("conversation-1")
        .contextVersion("v2")
        .prompt("show failed jobs")
        .build();
    assertThatThrownBy(() ->
            service.beginTurn(turnInput))
        .isInstanceOf(BizException.class);
    verify(mapper, never()).setTenantContext(anyString());
  }

  @Test
  @DisplayName("开始轮次仍先检查持久化开关，不因缺少所有者改变错误")
  void shouldCheckPersistenceBeforeOwner_whenBothInputsAreInvalid() {
    properties.getPersistence().setEnabled(false);
    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder().build();

    assertThatThrownBy(() -> service.beginTurn(turnInput))
        .isInstanceOfSatisfying(BizException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN);
          assertThat(exception.getMessageKey()).isEqualTo("error.ai.assistant_not_configured");
        });

    verify(mapper, never()).setTenantContext(any());
  }

  @Test
  @DisplayName("开始轮次缺少所有者仍由服务按原权限错误拒绝")
  void shouldRejectMissingOwner_whenContextVersionIsAlsoInvalid() {
    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .contextVersion("v2")
        .build();

    assertThatThrownBy(() -> service.beginTurn(turnInput))
        .isInstanceOfSatisfying(BizException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN);
          assertThat(exception.getMessageKey()).isEqualTo(ResultCode.FORBIDDEN.detailKey());
        });

    verify(mapper, never()).setTenantContext(any());
  }

  @Test
  @DisplayName("开始轮次缺少版本仍由服务按原参数错误拒绝")
  void shouldRejectMissingContextVersion_whenStartingTurn() {
    ConsoleAiTurnStartInput turnInput = ConsoleAiTurnStartInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .build();

    assertThatThrownBy(() -> service.beginTurn(turnInput))
        .isInstanceOfSatisfying(BizException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
          assertThat(exception.getMessageKey()).isEqualTo(ResultCode.INVALID_ARGUMENT.detailKey());
        });

    verify(mapper, never()).setTenantContext(any());
  }

  @Test
  @DisplayName("会话持久化关闭:列表查询被拒绝,且不访问数据库")
  void shouldRejectPersistenceWhenDisabled() {
    properties.getPersistence().setEnabled(false);

    assertThatThrownBy(() -> service.list("tenant-a", "operator-a", 10))
        .isInstanceOf(BizException.class);
    verify(mapper, never()).selectByOwner(anyString(), anyString(), anyInt());
  }

  @Test
  @DisplayName("会话分页:按更新时间与标识游标翻页,末页无游标")
  void shouldPageConversationsWithStableTimestampAndIdCursor() {
    properties.getPersistence().setConversationPageSize(2);
    Instant updatedAt = Instant.parse("2026-09-30T12:00:00Z");
    ConsoleAiConversationEntity third = conversation("conversation-3", updatedAt);
    ConsoleAiConversationEntity second = conversation("conversation-2", updatedAt);
    ConsoleAiConversationEntity first = conversation("conversation-1", updatedAt);
    when(mapper.selectPageByOwner(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "tenant-a") && java.util.Objects.equals(parameters.ownerUserId(), "operator-a") && parameters.beforeUpdatedAt() == null && parameters.beforeId() == null && java.util.Objects.equals(parameters.limit(), 3))))
        .thenReturn(List.of(third, second, first));
    when(mapper.selectPageByOwner(ConsoleAiConversationPageByOwnerQuery.builder()
        .tenantId("tenant-a")
        .ownerUserId("operator-a")
        .beforeUpdatedAt(updatedAt)
        .beforeId("conversation-2")
        .limit(3)
        .build()))
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
  @DisplayName("分页游标非法:在访问数据库之前即拒绝")
  void shouldRejectInvalidPageCursorBeforeDatabaseAccess() {
    assertThatThrownBy(() -> service.page("tenant-a", "operator-a", "not-a-cursor", 20))
        .isInstanceOf(BizException.class);
    verify(mapper, never())
        .selectPageByOwner(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.ownerUserId() != null && parameters.beforeId() != null));
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
