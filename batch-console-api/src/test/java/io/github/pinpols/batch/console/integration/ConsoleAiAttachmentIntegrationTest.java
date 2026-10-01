package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAttachmentEntity;
import io.github.pinpols.batch.console.domain.audit.infrastructure.ai.ConsoleAiAttachmentService;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConsoleAiAttachmentIntegrationTest extends AbstractIntegrationTest {
  private final ConsoleAiAttachmentMapper attachments;
  private final ConsoleAiAttachmentService attachmentService;
  private final ConsoleAiConversationMapper conversations;
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager transactionManager;

  ConsoleAiAttachmentIntegrationTest(
      ConsoleAiAttachmentMapper attachments,
      ConsoleAiAttachmentService attachmentService,
      ConsoleAiConversationMapper conversations,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.attachments = attachments;
    this.attachmentService = attachmentService;
    this.conversations = conversations;
    this.jdbc = jdbc;
    this.transactionManager = transactionManager;
  }

  @Test
  void bindsOwnedDraftAndKeepsCleanupKeyAfterConversationCascade() {
    String tenant = "ai-attachment-" + UUID.randomUUID();
    String otherTenant = "ai-attachment-" + UUID.randomUUID();
    UUID attachmentId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    String key = "ai/images/" + UUID.randomUUID();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    tx.executeWithoutResult(status -> {
      attachments.setTenantContext(tenant);
      jdbc.update("""
          INSERT INTO batch.console_ai_conversation
              (id, tenant_id, owner_user_id, context_version, expires_at)
          VALUES ('conversation-1', ?, 'owner-a', 'v1', CURRENT_TIMESTAMP + INTERVAL '1 day')
          """, tenant);
      jdbc.update("""
          INSERT INTO batch.console_ai_turn
              (tenant_id, conversation_id, turn_no, context_version, prompt_text)
          VALUES (?, 'conversation-1', 1, 'v1', 'ciphertext')
          """, tenant);
      ConsoleAiAttachmentEntity row = new ConsoleAiAttachmentEntity();
      row.setTenantId(tenant);
      row.setId(attachmentId);
      row.setOwnerUserId("owner-a");
      row.setClientAttachmentId(clientId);
      row.setInputSha256("0".repeat(64));
      row.setObjectKey(key);
      row.setByteSize(100L);
      row.setExpiresAt(Instant.now().plusSeconds(3600));
      assertThat(attachments.insert(row)).isEqualTo(1);
      assertThat(attachments.activeDraftCount(tenant, "owner-a")).isEqualTo(1);
      assertThat(attachments.activeDraftBytes(tenant, "owner-a")).isEqualTo(100L);
      assertThat(attachments.retainedBytesForUser(tenant, "owner-a")).isEqualTo(100L);
      assertThat(attachments.retainedBytesForTenant(tenant)).isEqualTo(100L);
      assertThat(attachments.lockUploadQuota(tenant)).isEqualTo(1);
      assertThat(attachments.markDraft(tenant, attachmentId, "image/png", 100, 16, 16))
          .isEqualTo(1);
      assertThat(attachments.bind(
              tenant,
              attachmentId,
              "owner-b",
              "conversation-1",
              1,
              Instant.now().plusSeconds(3600)))
          .isZero();
      assertThat(attachments.bind(
              tenant,
              attachmentId,
              "owner-a",
              "conversation-1",
              1,
              Instant.now().plusSeconds(3600)))
          .isEqualTo(1);
      assertThat(attachments.byTurn(tenant, "conversation-1", 1)).hasSize(1);
      assertThat(attachments.byClientId(tenant, "owner-a", clientId).getId())
          .isEqualTo(attachmentId);
    });

    tx.executeWithoutResult(status -> {
      attachments.setTenantContext(otherTenant);
      assertThat(attachments.byId(otherTenant, attachmentId)).isNull();
    });

    tx.executeWithoutResult(status -> {
      attachments.setTenantContext(tenant);
      assertThat(attachments.enqueueForConversation(tenant, "conversation-1")).isEqualTo(1);
      assertThat(conversations.deleteConversation(tenant, "conversation-1", "owner-a"))
          .isEqualTo(1);
      assertThat(attachments.byId(tenant, attachmentId)).isNull();
      assertThat(attachments.cleanupKeys(tenant, 10)).containsExactly(key);
    });
  }

  @Test
  void draftImageCannotBeReadAsAFileService() {
    String tenant = "ai-private-" + UUID.randomUUID();
    UUID id = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      attachments.setTenantContext(tenant);
      ConsoleAiAttachmentEntity row = new ConsoleAiAttachmentEntity();
      row.setTenantId(tenant);
      row.setId(id);
      row.setOwnerUserId("owner-a");
      row.setClientAttachmentId(UUID.randomUUID());
      row.setInputSha256("0".repeat(64));
      row.setObjectKey("ai/images/" + UUID.randomUUID());
      row.setByteSize(100L);
      row.setExpiresAt(Instant.now().plusSeconds(3600));
      assertThat(attachments.insert(row)).isEqualTo(1);
      assertThat(attachments.markDraft(tenant, id, "image/png", 100, 16, 16)).isEqualTo(1);
    });

    assertThatThrownBy(() -> attachmentService.content(tenant, "owner-a", id))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
    assertThatThrownBy(() -> attachmentService.content(tenant, "owner-b", id))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
  }
}
