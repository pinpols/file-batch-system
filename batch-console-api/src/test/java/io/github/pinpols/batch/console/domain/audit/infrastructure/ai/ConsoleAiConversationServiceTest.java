package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import java.time.OffsetDateTime;
import java.util.List;
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
    when(mapper.selectForUpdate("conversation-1")).thenReturn(null);
    when(mapper.selectRecentCompleteTurns("conversation-1", 12)).thenReturn(List.of());
    when(mapper.allocateTurnNo(anyString(), anyString(), any(OffsetDateTime.class), anyString()))
        .thenReturn(1L);

    ConsoleAiConversationService.StartedTurn turn =
        service.beginTurn("tenant-a", "operator-a", "conversation-1", "v1", "show failed jobs");

    assertThat(turn.conversationId()).isEqualTo("conversation-1");
    assertThat(turn.turnNo()).isEqualTo(1L);
    verify(mapper).setTenantContext("tenant-a");
    verify(mapper).insertConversation(any(ConsoleAiConversationEntity.class));
    verify(mapper).insertTurn(any());
  }

  @Test
  void shouldDenyConversationOwnedByAnotherUser() {
    ConsoleAiConversationEntity conversation = new ConsoleAiConversationEntity();
    conversation.setOwnerUserId("operator-b");
    when(mapper.selectForUpdate("conversation-1")).thenReturn(conversation);

    assertThatThrownBy(() ->
            service.beginTurn("tenant-a", "operator-a", "conversation-1", "v1", "show failed jobs"))
        .isInstanceOf(BizException.class);
    verify(mapper, never()).insertTurn(any());
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
    verify(mapper, never()).selectByOwner(anyString(), anyInt());
  }
}
