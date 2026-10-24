package io.github.pinpols.batch.console.integration;

import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiConversationAllocateTurnNoParam;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiConversationPageByOwnerQuery;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiConversationTurnsQuery;


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
          mapper.selectPageByOwner(ConsoleAiConversationPageByOwnerQuery.builder()
              .tenantId(tenantA)
              .ownerUserId("owner-a")
              .beforeUpdatedAt(null)
              .beforeId(null)
              .limit(2)
              .build());
      assertThat(first)
          .extracting(ConsoleAiConversationEntity::getId)
          .containsExactly("conversation-3", "conversation-2");

      List<ConsoleAiConversationEntity> second = mapper.selectPageByOwner(
          ConsoleAiConversationPageByOwnerQuery.builder()
              .tenantId(tenantA)
              .ownerUserId("owner-a")
              .beforeUpdatedAt(first.get(1).getUpdatedAt())
              .beforeId(first.get(1).getId())
              .limit(2)
              .build());
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
      assertThat(mapper.selectTurns(ConsoleAiConversationTurnsQuery.builder()
          .tenantId(tenantA)
          .conversationId("conversation-3")
          .ownerUserId("owner-a")
          .beforeTurnNo(null)
          .limit(20)
          .build()))
          .hasSize(1);
      assertThat(mapper.selectTurns(ConsoleAiConversationTurnsQuery.builder()
          .tenantId(tenantA)
          .conversationId("conversation-expired")
          .ownerUserId("owner-a")
          .beforeTurnNo(null)
          .limit(20)
          .build()))
          .isEmpty();
      assertThat(mapper.selectRecentCompleteTurns(tenantA, "conversation-3", 12))
          .hasSize(1);
      assertThat(mapper.allocateTurnNo(
              ConsoleAiConversationAllocateTurnNoParam.builder()
                  .tenantId(tenantA)
                  .conversationId("conversation-3")
                  .ownerUserId("owner-a")
                  .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))
                  .contextVersion("v1")
                  .build()))
          .isEqualTo(1L);
      assertThat(mapper.selectForUpdate(tenantB, "conversation-3").getNextTurnNo())
          .isEqualTo(1L);
    });
  }

  private void insert(
      String tenantId, String ownerUserId, String id, Instant updatedAt, boolean expired) {
    final String insertConversationSql = """
    INSERT INTO batch.console_ai_conversation
    (id, tenant_id, owner_user_id, context_version, created_at, updated_at, expires_at)
    VALUES (?, ?, ?, 'v1', ?, ?, ?)
    """;
    Timestamp createdAt = Timestamp.from(updatedAt);
    Timestamp lastUpdatedAt = Timestamp.from(updatedAt);
    Instant expirationTime = Instant.now().plusSeconds(expired ? -60 : 3600);
    Timestamp expiresAt = Timestamp.from(expirationTime);
    jdbcTemplate.update(
        insertConversationSql, id, tenantId, ownerUserId, createdAt, lastUpdatedAt, expiresAt);
  }

  private void insertTurn(String tenantId, String conversationId) {
    String insertConversationTurnSql = """
            INSERT INTO batch.console_ai_turn
                (tenant_id, conversation_id, turn_no, context_version, prompt_text, turn_status)
            VALUES (?, ?, 1, 'v1', 'encrypted-test-value', 'COMPLETE')
            """;
    jdbcTemplate.update(insertConversationTurnSql, tenantId, conversationId);
  }
}
