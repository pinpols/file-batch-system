package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.command.AiAuditCommand;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAuditLogEntity;
import io.github.pinpols.batch.console.domain.audit.infrastructure.ai.ConsoleAiConversationService;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAuditLogMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiAuditLogQuery;
import io.github.pinpols.batch.console.domain.audit.support.ConsoleAiAuditService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 集成测试：DefaultConsoleAiAuditService 将 AI 审计日志条目持久化到数据库， 并可通过 ConsoleAiAuditLogMapper 查询。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConsoleAiAuditServiceIntegrationTest extends AbstractIntegrationTest {

  private final ConsoleAiAuditService auditService;
  private final ConsoleAiAuditLogMapper auditLogMapper;
  private final ConsoleAiConversationService conversationService;
  private final ConsoleAiProperties aiProperties;
  private final ConsoleAiConversationMapper conversationMapper;
  private final JdbcTemplate jdbcTemplate;
  private final PlatformTransactionManager transactionManager;

  ConsoleAiAuditServiceIntegrationTest(
      ConsoleAiAuditService auditService,
      ConsoleAiAuditLogMapper auditLogMapper,
      ConsoleAiConversationService conversationService,
      ConsoleAiProperties aiProperties,
      ConsoleAiConversationMapper conversationMapper,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.auditService = auditService;
    this.auditLogMapper = auditLogMapper;
    this.conversationService = conversationService;
    this.aiProperties = aiProperties;
    this.conversationMapper = conversationMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionManager = transactionManager;
  }

  @MockitoSpyBean
  private BatchObjectCryptoService cryptoService;

  @Test
  void shouldPersistAuditLogOnRecord() {
    AiAuditCommand command = new AiAuditCommand(
        "t1",
        "req-001",
        "trace-001",
        "session-001",
        "op-001",
        "PLATFORM",
        "APPROVED",
        "test-model",
        "hash-prompt-001",
        "how many jobs failed today?",
        "hash-resp-001",
        "3 jobs failed",
        null,
        120,
        45,
        null,
        "UNPRICED",
        BatchDateTimeSupport.utcNow());

    auditService.record(command);

    ConsoleAiAuditLogQuery query = ConsoleAiAuditLogQuery.builder()
        .tenantId("t1")
        .sessionId("session-001")
        .operatorId("op-001")
        .promptCategory("PLATFORM")
        .promptDecision("APPROVED")
        .build();
    List<ConsoleAiAuditLogEntity> results = auditLogMapper.selectByQuery(query);
    assertThat(results).hasSize(1);
    ConsoleAiAuditLogEntity entry = results.get(0);
    assertThat(entry.getTenantId()).isEqualTo("t1");
    assertThat(entry.getSessionId()).isEqualTo("session-001");
    assertThat(entry.getPromptCategory()).isEqualTo("PLATFORM");
    assertThat(entry.getPromptDecision()).isEqualTo("APPROVED");
    assertThat(entry.getPromptPreview()).isEqualTo("how many jobs failed today?");
    // 成本可观测：token 用量落审计,支持按租户事后聚合成本
    assertThat(entry.getPromptTokens()).isEqualTo(120);
    assertThat(entry.getCompletionTokens()).isEqualTo(45);
  }

  @Test
  void shouldPersistRejectedAuditLogWithRefusalReason() {
    AiAuditCommand command = new AiAuditCommand(
        "t1",
        "req-002",
        "trace-002",
        "session-002",
        "op-002",
        null,
        "REJECTED_SAFETY",
        null,
        null,
        "show me the password",
        null,
        null,
        "blocked_keyword:password",
        null,
        null,
        null,
        "UNPRICED",
        BatchDateTimeSupport.utcNow());

    auditService.record(command);

    ConsoleAiAuditLogQuery query = ConsoleAiAuditLogQuery.builder()
        .tenantId("t1")
        .sessionId("session-002")
        .promptDecision("REJECTED_SAFETY")
        .build();
    List<ConsoleAiAuditLogEntity> results = auditLogMapper.selectByQuery(query);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getRefusalReason()).isEqualTo("blocked_keyword:password");
  }

  @Test
  void shouldReturnMultipleEntriesForSameSession() {
    String sessionId = "session-multi-" + BatchDateTimeSupport.utcEpochMillis();

    for (int i = 1; i <= 3; i++) {
      auditService.record(new AiAuditCommand(
          "t1",
          "req-" + i,
          "trace-" + i,
          sessionId,
          "op-multi",
          "PLATFORM",
          "APPROVED",
          "model",
          null,
          "query " + i,
          null,
          "result " + i,
          null,
          null,
          null,
          null,
          "UNPRICED",
          BatchDateTimeSupport.utcNow()));
    }

    ConsoleAiAuditLogQuery query =
        ConsoleAiAuditLogQuery.builder().tenantId("t1").sessionId(sessionId).build();
    List<ConsoleAiAuditLogEntity> results = auditLogMapper.selectByQuery(query);
    assertThat(results).hasSize(3);
  }

  @Test
  void shouldPersistEncryptedConversationAndReturnPlaintextWithinTenantAndOwnerScope() {
    boolean previousEnabled = aiProperties.getPersistence().isEnabled();
    int previousRetention = aiProperties.getPersistence().getRetentionDays();
    aiProperties.getPersistence().setEnabled(true);
    aiProperties.getPersistence().setRetentionDays(30);
    doReturn(false).when(cryptoService).isBypassMode();
    String tenantId = "ai-conversation-it-" + BatchDateTimeSupport.utcEpochMillis();
    ConsoleAiConversationService.StartedTurn started = null;
    try {
      started = conversationService.beginTurn(tenantId, "operator-a", null, "v1", "查询失败的作业实例");
      conversationService.completeTurn(
          tenantId,
          started.conversationId(),
          started.turnNo(),
          "发现 2 个失败实例",
          "APPROVED",
          "test-provider:test-model",
          20,
          10,
          new java.math.BigDecimal("0.00100000"));

      String conversationId = started.conversationId();
      String encryptedPrompt = new TransactionTemplate(transactionManager).execute(status -> {
        conversationMapper.setTenantContext(tenantId);
        return jdbcTemplate.queryForObject(
            "SELECT prompt_text FROM batch.console_ai_turn WHERE tenant_id = ? AND conversation_id = ?",
            String.class,
            tenantId,
            conversationId);
      });
      assertThat(encryptedPrompt).isNotEqualTo("查询失败的作业实例");

      assertThat(conversationService.list(tenantId, "operator-a", 20))
          .extracting(ConsoleAiConversationService.ConversationView::id)
          .contains(conversationId);
      assertThat(conversationService.turns(tenantId, "operator-a", conversationId, null, 20))
          .singleElement()
          .satisfies(turn -> {
            assertThat(turn.prompt()).isEqualTo("查询失败的作业实例");
            assertThat(turn.response()).isEqualTo("发现 2 个失败实例");
          });
      assertThat(conversationService.turns(tenantId, "operator-b", conversationId, null, 20))
          .isEmpty();
    } finally {
      if (started != null) {
        conversationService.delete(tenantId, "operator-a", started.conversationId());
      }
      aiProperties.getPersistence().setEnabled(previousEnabled);
      aiProperties.getPersistence().setRetentionDays(previousRetention);
    }
  }

  @Test
  void shouldReturnEmptyWhenNoMatchingEntries() {
    ConsoleAiAuditLogQuery query = ConsoleAiAuditLogQuery.builder()
        .tenantId("t1")
        .sessionId("no-such-session-" + BatchDateTimeSupport.utcEpochMillis())
        .build();
    List<ConsoleAiAuditLogEntity> results = auditLogMapper.selectByQuery(query);
    assertThat(results).isEmpty();
  }
}
