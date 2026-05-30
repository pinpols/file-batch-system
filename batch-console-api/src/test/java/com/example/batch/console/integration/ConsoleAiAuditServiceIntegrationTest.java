package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.audit.command.AiAuditCommand;
import com.example.batch.console.domain.audit.entity.ConsoleAiAuditLogEntity;
import com.example.batch.console.domain.audit.mapper.ConsoleAiAuditLogMapper;
import com.example.batch.console.domain.audit.query.ConsoleAiAuditLogQuery;
import com.example.batch.console.domain.audit.support.ConsoleAiAuditService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 集成测试：DefaultConsoleAiAuditService 将 AI 审计日志条目持久化到数据库， 并可通过 ConsoleAiAuditLogMapper 查询。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsoleAiAuditServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ConsoleAiAuditService auditService;

  @Autowired private ConsoleAiAuditLogMapper auditLogMapper;

  @Test
  void shouldPersistAuditLogOnRecord() {
    AiAuditCommand command =
        new AiAuditCommand(
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
            BatchDateTimeSupport.utcNow());

    auditService.record(command);

    ConsoleAiAuditLogQuery query =
        ConsoleAiAuditLogQuery.builder()
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
  }

  @Test
  void shouldPersistRejectedAuditLogWithRefusalReason() {
    AiAuditCommand command =
        new AiAuditCommand(
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
            BatchDateTimeSupport.utcNow());

    auditService.record(command);

    ConsoleAiAuditLogQuery query =
        ConsoleAiAuditLogQuery.builder()
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
      auditService.record(
          new AiAuditCommand(
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
              BatchDateTimeSupport.utcNow()));
    }

    ConsoleAiAuditLogQuery query =
        ConsoleAiAuditLogQuery.builder().tenantId("t1").sessionId(sessionId).build();
    List<ConsoleAiAuditLogEntity> results = auditLogMapper.selectByQuery(query);
    assertThat(results).hasSize(3);
  }

  @Test
  void shouldReturnEmptyWhenNoMatchingEntries() {
    ConsoleAiAuditLogQuery query =
        ConsoleAiAuditLogQuery.builder()
            .tenantId("t1")
            .sessionId("no-such-session-" + BatchDateTimeSupport.utcEpochMillis())
            .build();
    List<ConsoleAiAuditLogEntity> results = auditLogMapper.selectByQuery(query);
    assertThat(results).isEmpty();
  }
}
