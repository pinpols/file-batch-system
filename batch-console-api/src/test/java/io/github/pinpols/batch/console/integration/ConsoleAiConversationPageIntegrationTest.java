package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("AI 会话分页查询映射: 时间戳并列,租户与属主隔离,过期会话过滤")
class ConsoleAiConversationPageIntegrationTest extends AbstractIntegrationTest {

  private final ConsoleAiConversationMapper mapper;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  ConsoleAiConversationPageIntegrationTest(
      ConsoleAiConversationMapper mapper,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.mapper = mapper;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Test
  @DisplayName("游标分页: 时间戳并列时按既定顺序翻页,并隔离租户,属主与过期会话")
  void shouldPageByCursorAndRespectTenantOwnerExpiry_whenTimestampsTie() {
    String tenantA = "ai-page-a-" + UUID.randomUUID();
    String tenantB = "ai-page-b-" + UUID.randomUUID();
    Instant updatedAt = Instant.now().minusSeconds(60);
    transactionTemplate.executeWithoutResult(status -> {
      mapper.setTenantContext(tenantA);
      insert(tenantA, "owner-a", "conversation-3", updatedAt, false);
      insert(tenantA, "owner-a", "conversation-2", updatedAt, false);
      insert(tenantA, "owner-a", "conversation-1", updatedAt, false);
      insert(tenantA, "owner-a", "conversation-expired", updatedAt, true);
      insert(tenantA, "owner-b", "conversation-other-owner", updatedAt, false);
      insertTurn(tenantA, "conversation-3");
      insertTurn(tenantA, "conversation-expired");
    });
    transactionTemplate.executeWithoutResult(status -> {
      mapper.setTenantContext(tenantB);
      insert(tenantB, "owner-a", "conversation-other-tenant", updatedAt, false);
      insert(tenantB, "owner-a", "conversation-3", updatedAt, false);
      insertTurn(tenantB, "conversation-3");
    });

    transactionTemplate.executeWithoutResult(status -> {
      mapper.setTenantContext(tenantA);
      List<ConsoleAiConversationEntity> first =
          mapper.selectPageByOwner(tenantA, "owner-a", null, null, 2);
      assertThat(first)
          .extracting(ConsoleAiConversationEntity::getId)
          .containsExactly("conversation-3", "conversation-2");

      List<ConsoleAiConversationEntity> second = mapper.selectPageByOwner(
          tenantA, "owner-a", first.get(1).getUpdatedAt(), first.get(1).getId(), 2);
      assertThat(second)
          .extracting(ConsoleAiConversationEntity::getId)
          .containsExactly("conversation-1");
      assertThat(mapper.selectByOwner(tenantA, "owner-a", 20))
          .extracting(ConsoleAiConversationEntity::getId)
          .containsExactly("conversation-3", "conversation-2", "conversation-1");
      assertThat(mapper.selectForUpdate(tenantA, "conversation-3").getTenantId())
          .isEqualTo(tenantA);
      assertThat(mapper.selectActiveByOwner(tenantA, "conversation-3", "owner-a"))
          .isNotNull();
      assertThat(mapper.selectActiveByOwner(tenantA, "conversation-expired", "owner-a"))
          .isNull();
      assertThat(mapper.selectActiveByOwner(tenantA, "conversation-other-owner", "owner-a"))
          .isNull();
      assertThat(mapper.selectActiveByOwner(tenantA, "conversation-other-tenant", "owner-a"))
          .isNull();
      assertThat(mapper.selectTurns(tenantA, "conversation-3", "owner-a", null, 20))
          .hasSize(1);
      assertThat(mapper.selectTurns(tenantA, "conversation-expired", "owner-a", null, 20))
          .isEmpty();
      assertThat(mapper.selectRecentCompleteTurns(tenantA, "conversation-3", 12))
          .hasSize(1);
      assertThat(mapper.allocateTurnNo(
              tenantA,
              "conversation-3",
              "owner-a",
              OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
              "v1"))
          .isEqualTo(1L);
      assertThat(mapper.selectForUpdate(tenantB, "conversation-3").getNextTurnNo())
          .isEqualTo(1L);
    });
  }

  private void insert(
      String tenantId, String ownerUserId, String id, Instant updatedAt, boolean expired) {
    jdbcTemplate.update(
        """
            INSERT INTO batch.console_ai_conversation
                (id, tenant_id, owner_user_id, context_version, created_at, updated_at, expires_at)
            VALUES (?, ?, ?, 'v1', ?, ?, ?)
            """,
        id,
        tenantId,
        ownerUserId,
        Timestamp.from(updatedAt),
        Timestamp.from(updatedAt),
        Timestamp.from(Instant.now().plusSeconds(expired ? -60 : 3600)));
  }

  private void insertTurn(String tenantId, String conversationId) {
    jdbcTemplate.update("""
            INSERT INTO batch.console_ai_turn
                (tenant_id, conversation_id, turn_no, context_version, prompt_text, turn_status)
            VALUES (?, ?, 1, 'v1', 'encrypted-test-value', 'COMPLETE')
            """, tenantId, conversationId);
  }
}
