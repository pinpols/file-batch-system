package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = E2eExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.export.worker-type=EXPORT")
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.EXPORT_TEMPLATE_SEED)
@DisplayName("四分片导出产物完整性")
class ExportPartitionContentE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport outbox;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("exportBusinessDataSource")
  private DataSource businessDataSource;

  @Test
  @DisplayName("旧范围配置下四分片导出所有业务记录且不重复")
  void shouldExportEveryRecordExactlyOnce_whenLegacyRangeSettingHasFourPartitions()
      throws Exception {
    String batchNo = "E2E-PARTITION-" + System.nanoTime();
    final String insertBatchSql = """
        insert into biz.settlement_batch
          (tenant_id, batch_no, biz_date, accounting_period, batch_status,
           total_record_count, total_amount, currency)
        values (?, ?, date '2026-01-15', '202601', 'READY', 40, 0, 'CNY') returning id
        """;
    final String insertDetailsSql = """
        insert into biz.settlement_detail
          (tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
           gross_amount, fee_amount, net_amount, currency, settlement_status)
        select ?, ?, 'E2E-PART-' || lpad(n::text, 3, '0'), 'C-' || n,
               date '2026-01-15', '202601', 100, 5, 95, 'CNY', 'READY'
        from generate_series(1, 40) n
        """;
    final String updateTemplateSql = """
        update batch.file_template_config
        set query_param_schema = jsonb_set(query_param_schema, '{partition_keyset_range}', 'true'::jsonb),
            page_size = 5
        where tenant_id = ? and template_code = 'EXP-SETTLEMENT-JSON'
        """;
    final String selectTaskStatusesSql = """
        select task.task_status from batch.job_task task
        join batch.job_instance ji on ji.tenant_id = task.tenant_id and ji.id = task.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ? order by task.id
        """;
    final String selectOutputFilesSql = """
        select fr.storage_bucket, fr.storage_path from batch.file_record fr
        where fr.tenant_id = ? and fr.source_ref = ? and fr.file_category = 'OUTPUT'
          and fr.source_type = 'GENERATED'
        order by fr.id
        """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Long batchId = businessJdbc.queryForObject(insertBatchSql, Long.class, TENANT, batchNo);
    businessJdbc.update(insertDetailsSql, TENANT, batchId);
    assertThat(jdbcTemplate.update(updateTemplateSql, TENANT)).isEqualTo(1);

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
                jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API)
            .shardStrategy("STATIC"));
    Map<String, Object> params = Map.of(
        "batchNo", batchNo,
        "templateCode", "EXP-SETTLEMENT-JSON",
        "bizDate", "2026-01-15",
        "bizType", "SETTLEMENT",
        "fileCode", "e2e-partition-export",
        "partitionCount", 4);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-partition-export",
        params));
    outbox.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForList(
                selectTaskStatusesSql, String.class, TENANT, seed.dedupKey()))
            .containsExactly("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"));

    List<Map<String, Object>> files =
        jdbcTemplate.queryForList(selectOutputFilesSql, TENANT, batchNo);
    assertThat(files).hasSize(4);
    assertThat(files.stream().map(file -> file.get("storage_path"))).doesNotHaveDuplicates();
    List<String> actual = new ArrayList<>();
    for (Map<String, Object> file : files) {
      String bucket = (String) file.get("storage_bucket");
      String path = (String) file.get("storage_path");
      String key = path.startsWith(bucket + "/") ? path.substring(bucket.length() + 1) : path;
      JsonNode details = objectMapper.readTree(readObject(bucket, key)).path("details");
      assertThat(details.isArray()).isTrue();
      for (JsonNode detail : details) {
        actual.add(detail.path("settlement_no").asText());
      }
    }
    List<String> expected =
        IntStream.rangeClosed(1, 40).mapToObj(n -> "E2E-PART-%03d".formatted(n)).toList();
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
  }
}
