package com.example.batch.orchestrator.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("分区后 outbox insert 幂等:同 (tenant,event_key) 二次插入静默跳过")
class OutboxInsertDedupIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OutboxEventMapper outboxEventMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private OutboxEventEntity entity(String eventKey) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.setTenantId("ta");
    e.setAggregateType("JOB_TASK");
    e.setAggregateId(1L);
    e.setEventType("TASK_READY");
    e.setEventKey(eventKey);
    e.setPayloadJson("{}");
    e.setPublishStatus("NEW");
    e.setPublishAttempt(0);
    return e;
  }

  @Test
  void shouldSkipSecondInsert_whenSameTenantAndEventKey() {
    String key = "ta:dedup-it:" + System.nanoTime();
    OutboxEventEntity first = entity(key);
    outboxEventMapper.insert(first);
    assertThat(first.getId()).as("首插回填 id").isNotNull();

    OutboxEventEntity second = entity(key);
    outboxEventMapper.insert(second);
    assertThat(second.getId()).as("重复插入不回填 id(与旧 DO NOTHING 契约一致)").isNull();

    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM batch.outbox_event WHERE tenant_id='ta' AND event_key=?",
            Integer.class,
            key);
    assertThat(rows).as("仅 1 行").isEqualTo(1);
  }
}
