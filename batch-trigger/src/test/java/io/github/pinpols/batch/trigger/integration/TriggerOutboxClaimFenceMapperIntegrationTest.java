package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.param.TriggerOutboxEventMarkFailedParam;
import io.github.pinpols.batch.trigger.domain.param.TriggerOutboxEventMarkPublishingBatchParam;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Trigger Outbox 领取轮次围栏:旧投递者不得回写新领取")
class TriggerOutboxClaimFenceMapperIntegrationTest extends AbstractIntegrationTest {

  private final TriggerOutboxEventMapper mapper;

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  TriggerOutboxClaimFenceMapperIntegrationTest(
      TriggerOutboxEventMapper mapper, JdbcTemplate jdbcTemplate) {
    this.mapper = mapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @Transactional
  @DisplayName("状态从 PUBLISHING 回收再领取后，旧成功回写应被拒绝")
  void shouldRejectStalePublisherAfterReclaim() {
    String tenantId = "outbox-fence-" + UUID.randomUUID();
    String requestId = "request-" + UUID.randomUUID();
    String insertTenantSql =
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')";
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    String insertWorkflowSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)
        values (?, ?, ?, 'TEST_CAL') returning id
        """;
    Long workflowId = jdbcTemplate.queryForObject(
        insertWorkflowSql, Long.class, tenantId, "workflow-" + requestId, "Workflow " + requestId);
    String insertRequestSql = """
        insert into batch.trigger_request
          (tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
           dedup_key, request_status, trace_id)
        values (?, ?, 'MANUAL', ?, ?, ?, 'ACCEPTED', ?)
        """;
    jdbcTemplate.update(
        insertRequestSql,
        tenantId,
        requestId,
        workflowId,
        LocalDate.of(2026, 10, 27),
        "dedup-" + requestId,
        "trace-" + requestId);
    TriggerOutboxEventEntity event = new TriggerOutboxEventEntity();
    event.setTenantId(tenantId);
    event.setRequestId(requestId);
    event.setPayload("{}");
    event.setPublishAttempt(0);
    event.setNextPublishAt(Instant.now().plusSeconds(3600));
    mapper.insert(event);

    String firstToken = UUID.randomUUID().toString();
    List<Long> firstClaim = mapper.markPublishingBatch(claim(event.getId(), firstToken));
    assertThat(firstClaim).containsExactly(event.getId());
    String expireClaimSql = """
        update batch.trigger_outbox_event
        set updated_at = CURRENT_TIMESTAMP - interval '1 day' where id = ?
        """;
    jdbcTemplate.update(expireClaimSql, event.getId());
    int recovered = mapper.resetStalePublishing(
        OutboxPublishStatus.PUBLISHING.code(),
        OutboxPublishStatus.FAILED.code(),
        "stale publishing test recovery",
        60);
    assertThat(recovered).isEqualTo(1);
    String secondToken = UUID.randomUUID().toString();
    List<Long> secondClaim = mapper.markPublishingBatch(claim(event.getId(), secondToken));
    assertThat(secondClaim).containsExactly(event.getId());
    String selectStatusSql = "select publish_status from batch.trigger_outbox_event where id = ?";
    String currentStatus =
        jdbcTemplate.queryForObject(selectStatusSql, String.class, event.getId());
    assertThat(currentStatus).isEqualTo(OutboxPublishStatus.PUBLISHING.code());
    String selectTokenSql =
        "select publish_claim_token::text from batch.trigger_outbox_event where id = ?";
    String currentToken = jdbcTemplate.queryForObject(selectTokenSql, String.class, event.getId());
    assertThat(currentToken).isEqualTo(secondToken);

    TriggerOutboxEventMarkFailedParam staleFailure = TriggerOutboxEventMarkFailedParam.builder()
        .id(event.getId())
        .status(OutboxPublishStatus.FAILED.code())
        .lastError("old publisher failure")
        .nextPublishAt(Instant.now())
        .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
        .claimToken(firstToken)
        .build();
    assertThat(mapper.markFailed(staleFailure)).isZero();

    int staleUpdated = mapper.markPublishedBatch(
        List.of(event.getId()),
        OutboxPublishStatus.PUBLISHED.code(),
        OutboxPublishStatus.PUBLISHING.code(),
        firstToken);

    assertThat(staleUpdated).isZero();
    int currentUpdated = mapper.markPublishedBatch(
        List.of(event.getId()),
        OutboxPublishStatus.PUBLISHED.code(),
        OutboxPublishStatus.PUBLISHING.code(),
        secondToken);
    assertThat(currentUpdated).isEqualTo(1);
    TriggerOutboxEventEntity published =
        mapper.selectByTenantAndRequest(tenantId, requestId).get(0);
    assertThat(published.getPublishStatus()).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
    assertThat(published.getPublishAttempt()).isZero();
    assertThat(published.getPublishClaimToken()).isNull();
    String archiveSql = """
        insert into archive.trigger_outbox_event_archive
        select * from batch.trigger_outbox_event where id = ?
        """;
    int archived = jdbcTemplate.update(archiveSql, event.getId());
    assertThat(archived).isEqualTo(1);
    String selectArchivedStatusSql =
        "select publish_status from archive.trigger_outbox_event_archive where id = ?";
    String archivedStatus =
        jdbcTemplate.queryForObject(selectArchivedStatusSql, String.class, event.getId());
    assertThat(archivedStatus).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
  }

  private static TriggerOutboxEventMarkPublishingBatchParam claim(Long id, String token) {
    return TriggerOutboxEventMarkPublishingBatchParam.builder()
        .ids(List.of(id))
        .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
        .pendingStatus1(OutboxPublishStatus.NEW.code())
        .pendingStatus2(OutboxPublishStatus.FAILED.code())
        .claimToken(token)
        .build();
  }
}
