package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.worker.import.preprocess-spool-bytes=1"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@DisplayName("带 BOM 的对象范围分片导入端到端验证")
class ImportBomPartitionE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);
  private final String auditName = "bom_range_" + Long.toUnsignedString(System.nanoTime());

  private final WorkflowRunLaunchService launchService;
  private final JdbcTemplate jdbcTemplate;
  private final E2eOutboxPublishSupport outbox;
  private final DataSource businessDataSource;

  @Autowired
  ImportBomPartitionE2eIT(
      WorkflowRunLaunchService launchService,
      JdbcTemplate jdbcTemplate,
      E2eOutboxPublishSupport outbox,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.launchService = launchService;
    this.jdbcTemplate = jdbcTemplate;
    this.outbox = outbox;
    this.businessDataSource = businessDataSource;
  }

  @MockitoSpyBean
  private BatchObjectStore objectStore;

  @AfterEach
  void removeAuditProbe() {
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    businessJdbc.execute("drop trigger if exists " + auditName + " on biz.customer_account");
    businessJdbc.execute("drop function if exists biz." + auditName + "()");
    businessJdbc.execute("drop table if exists biz." + auditName);
  }

  @Test
  @DisplayName("两个范围分片导入带 BOM 文件，每条客户记录实际写入一次")
  void shouldWriteEachBomDelimitedRangeRecordOnce() throws Exception {
    String insertFileSql = """
        insert into batch.file_record (
          tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
          file_format_type, charset, file_size_bytes, checksum_type, storage_type,
          storage_bucket, storage_path, source_type, file_status, biz_date
        ) values (?, ?, 'CUSTOMER', 'INPUT', ?, ?, 'FIXED_WIDTH', 'UTF-8', ?,
                  'NONE', 'S3', ?, ?, 'SYSTEM', 'RECEIVED', ?) returning id
        """;
    String selectTasksSql = """
        select task.task_status from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
          and instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ? order by task.id
        """;
    String selectInstanceSql = """
        select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?
        """;
    String auditTableSql = "create table biz." + auditName + " (customer_no text not null)";
    String auditFunctionSql = """
        create function biz.%s() returns trigger language plpgsql as $$
        begin
          insert into biz.%s (customer_no) values (new.customer_no);
          return new;
        end $$
        """.formatted(auditName, auditName);
    String auditTriggerSql = """
        create trigger %s after insert or update on biz.customer_account
        for each row when (new.customer_no in ('A001', 'A002', 'A003', 'A004'))
        execute function biz.%s()
        """.formatted(auditName, auditName);
    String selectWritesSql = """
        select customer_no, count(*)::int as writes from biz.%s
        group by customer_no order by customer_no
        """.formatted(auditName);
    String selectCustomersSql = """
        select customer_no, customer_name from biz.customer_account
        where tenant_id = ? and customer_no in ('A001', 'A002', 'A003', 'A004')
        order by customer_no
        """;
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    businessJdbc.execute(auditTableSql);
    businessJdbc.execute(auditFunctionSql);
    businessJdbc.execute(auditTriggerSql);

    StringBuilder text = new StringBuilder("\uFEFF");
    for (int index = 1; index <= 4; index++) {
      text.append(String.format(
          Locale.ROOT,
          "%-4s%-20s%-9s%010.2f%-3s%-24s%-12s%-8s%-8s%-3s%n",
          "A00" + index,
          "Customer " + index,
          "PERSONAL",
          100.0,
          "CNY",
          "",
          "",
          "ACTIVE",
          "20260101",
          ""));
    }
    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
    String suffix = Long.toUnsignedString(System.nanoTime());
    String key = "ingress/" + TENANT + "/bom-range-" + suffix + ".txt";
    ensureBucket(s3Bucket());
    putObject(s3Bucket(), key, bytes);
    Long fileId = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        TENANT,
        "bom-" + suffix,
        "bom.txt",
        "bom.txt",
        bytes.length,
        s3Bucket(),
        key,
        BIZ_DATE);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
                jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API)
            .shardStrategy("STATIC")
            .bizDate(BIZ_DATE));
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", fileId);
    params.put("fileFormatType", "FIXED_WIDTH");
    params.put("templateCode", "IMP-CUSTOMER-FW");
    params.put("bizType", "CUSTOMER");
    params.put("storageType", "S3");
    params.put("storageBucket", s3Bucket());
    params.put("storagePath", key);
    params.put("partitionCount", 2);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        "bom-range-" + suffix,
        params));
    outbox.publishAllPending(TENANT);
    await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(200)).until(() -> {
      String status =
          jdbcTemplate.queryForObject(selectInstanceSql, String.class, TENANT, seed.dedupKey());
      return "SUCCESS".equals(status) || "FAILED".equals(status);
    });
    String instanceStatus =
        jdbcTemplate.queryForObject(selectInstanceSql, String.class, TENANT, seed.dedupKey());
    assertThat(instanceStatus).isEqualTo("SUCCESS");
    List<String> statuses =
        jdbcTemplate.queryForList(selectTasksSql, String.class, TENANT, seed.dedupKey());
    assertThat(statuses).containsExactly("SUCCESS", "SUCCESS");
    verify(objectStore, atLeastOnce()).getFrom(s3Bucket(), key, 0);
    verify(objectStore, atLeastOnce()).getFrom(s3Bucket(), key, bytes.length / 2L);
    List<Map<String, Object>> writes = businessJdbc.queryForList(selectWritesSql);
    assertThat(writes)
        .containsExactly(
            Map.of("customer_no", "A001", "writes", 1), Map.of("customer_no", "A002", "writes", 1),
            Map.of("customer_no", "A003", "writes", 1), Map.of("customer_no", "A004", "writes", 1));
    List<Map<String, Object>> customers = businessJdbc.queryForList(selectCustomersSql, TENANT);
    assertThat(customers)
        .containsExactly(
            Map.of("customer_no", "A001", "customer_name", "Customer 1"),
            Map.of("customer_no", "A002", "customer_name", "Customer 2"),
            Map.of("customer_no", "A003", "customer_name", "Customer 3"),
            Map.of("customer_no", "A004", "customer_name", "Customer 4"));
  }
}
