package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("作业关联文件查询")
class FileJobAssociationQueryIntegrationTest extends AbstractMutationIntegrationTest {
  @Test
  @DisplayName("作业关联文件仅包含目标作业的文件且分页计数一致")
  void shouldFilterFilesByJobAssociation() throws Exception {
    String tenant = "file-job-" + System.nanoTime();
    Long related = seedFile(tenant, "related.csv");
    Long unrelated = seedFile(tenant, "unrelated.csv");
    seedPipeline(tenant, "JOB_A", related);
    seedPipeline(tenant, "JOB_B", unrelated);
    seedPipeline(tenant + "-other", "JOB_A", seedFile(tenant + "-other", "foreign.csv"));
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("jobCode", "JOB_A")
            .queryParam("pageNo", 1)
            .queryParam("pageSize", 1)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode data = new ObjectMapper().readTree(body).path("data");
    assertThat(data.path("total").asLong()).isEqualTo(1);
    assertThat(data.path("items").get(0).path("fileName").asText()).isEqualTo("related.csv");
  }

  @Test
  @DisplayName("实例与分区关联均可查且重复关联不重复计数")
  void shouldIncludeInstanceAndPartitionAssociationsWithoutDuplicates() throws Exception {
    String tenant = "file-job-links-" + System.nanoTime();
    Long direct = seedFile(tenant, "direct.csv");
    Long partition = seedFile(tenant, "partition.csv");
    String insertJobSql = """
        insert into batch.job_definition (tenant_id,job_code,job_name,job_type,schedule_type,timezone)
        values (?, 'JOB_A', 'job', 'GENERAL', 'MANUAL', 'UTC') returning id
        """;
    Long job = jdbcTemplate.queryForObject(insertJobSql, Long.class, tenant);
    String insertInstanceSql = """
        insert into batch.job_instance (tenant_id,job_definition_id,job_code,instance_no,biz_date,
          trigger_type,instance_status,dedup_key,related_file_id)
        values (?, ?, 'JOB_A', 'instance', current_date, 'MANUAL', 'RUNNING', 'dedup', ?) returning id
        """;
    Long instance = jdbcTemplate.queryForObject(insertInstanceSql, Long.class, tenant, job, direct);
    String insertPartitionSql = """
        insert into batch.job_partition (tenant_id,job_instance_id,job_instance_biz_date,partition_no,
          partition_status,source_file_id)
        values (?, ?, current_date, 0, 'READY', ?)
        """;
    jdbcTemplate.update(insertPartitionSql, tenant, instance, partition);
    seedPipeline(tenant, "JOB_A", direct);
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("jobCode", "JOB_A")
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode data = new ObjectMapper().readTree(body).path("data");
    assertThat(data.path("total").asLong()).isEqualTo(2);
    assertThat(data.path("items").get(0).path("fileName").asText()).isEqualTo("partition.csv");
    assertThat(data.path("items").get(1).path("fileName").asText()).isEqualTo("direct.csv");
  }

  @Test
  @DisplayName("作业关联文件游标翻页并拒绝超长作业编码")
  void shouldPageRelatedFilesByCursorAndRejectOversizedJobCode() throws Exception {
    String tenant = "file-job-cursor-" + System.nanoTime();
    seedPipeline(tenant, "JOB_A", seedFile(tenant, "first.csv"));
    seedPipeline(tenant, "JOB_A", seedFile(tenant, "second.csv"));
    seedPipeline(tenant, "JOB_B", seedFile(tenant, "unrelated.csv"));
    ObjectMapper json = new ObjectMapper();
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("jobCode", "JOB_A")
            .queryParam("cursor", "")
            .queryParam("pageSize", 1)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode first = json.readTree(body).path("data");
    assertThat(first.path("items").get(0).path("fileName").asText()).isEqualTo("second.csv");
    assertThat(first.path("nextCursor").asText()).isNotBlank();
    String nextBody = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("jobCode", "JOB_A")
            .queryParam("cursor", first.path("nextCursor").asText())
            .queryParam("pageSize", 1)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(json.readTree(nextBody)
            .path("data")
            .path("items")
            .get(0)
            .path("fileName")
            .asText())
        .isEqualTo("first.csv");
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("jobCode", "x".repeat(129))
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private Long seedFile(String tenant, String name) {
    String insertFileSql = """
        insert into batch.file_record (tenant_id,file_category,file_name,file_format_type,storage_type,
           storage_path,source_type,file_status,biz_date)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', current_date) returning id
        """;
    Long fileId =
        jdbcTemplate.queryForObject(insertFileSql, Long.class, tenant, name, tenant + "/" + name);
    return fileId;
  }

  private void seedPipeline(String tenant, String jobCode, Long fileId) {
    String insertPipelineSql = """
        insert into batch.pipeline_instance (tenant_id,job_code,pipeline_type,system_pipeline_code,
          system_pipeline_version,file_id,run_status)
        values (?, ?, 'IMPORT', 'IMPORT', 1, ?, 'RUNNING')
        """;
    jdbcTemplate.update(insertPipelineSql, tenant, jobCode, fileId);
  }
}
