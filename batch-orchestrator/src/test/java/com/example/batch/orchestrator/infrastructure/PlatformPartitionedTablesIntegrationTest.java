package com.example.batch.orchestrator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("平台库分区守护:outbox_event / job_instance 必须是 RANGE 分区父表")
@SpringBootTest(
    classes = com.example.batch.orchestrator.BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PlatformPartitionedTablesIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void outboxEventAndJobInstance_shouldBePartitionedParents() {
    List<String> partitioned =
        jdbcTemplate.queryForList(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname='batch' AND c.relkind='p'",
            String.class);
    assertThat(partitioned).contains("outbox_event", "job_instance");
  }

  @Test
  void partitions_shouldCoverCurrentMonthAndDefault() {
    Integer outboxParts =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent='batch.outbox_event'::regclass",
            Integer.class);
    Integer jiParts =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent='batch.job_instance'::regclass",
            Integer.class);
    // 36 月分区 + default
    assertThat(outboxParts).as("outbox_event 分区数").isEqualTo(37);
    assertThat(jiParts).as("job_instance 分区数").isEqualTo(37);
  }
}
