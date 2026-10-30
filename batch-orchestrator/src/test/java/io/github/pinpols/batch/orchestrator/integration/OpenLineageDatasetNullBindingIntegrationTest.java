package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.OpenLineageDatasetMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Workflow 血缘数据集查询的空值和租户边界")
class OpenLineageDatasetNullBindingIntegrationTest extends AbstractIntegrationTest {
  private final OpenLineageDatasetMapper mapper;

  private final JdbcTemplate jdbc;

  @Autowired
  OpenLineageDatasetNullBindingIntegrationTest(OpenLineageDatasetMapper mapper, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("无作业实例时只查询指定租户和追踪的血缘数据集")
  void shouldFindOnlyTenantTraceWhenJobInstanceIsAbsent() {
    String tenant = "lineage-null-" + UUID.randomUUID();
    String otherTenant = "lineage-other-" + UUID.randomUUID();
    String trace = "trace-" + UUID.randomUUID();
    String insert = """
        insert into batch.file_record (tenant_id,file_code,biz_type,file_category,file_name,
          file_format_type,storage_type,storage_path,source_type,file_status,biz_date,trace_id)
        values (?,?,'GL','OUTPUT',?,'BINARY','LOCAL',?,'SYSTEM','RECEIVED',?,?)
        returning id
        """;
    String delete = "delete from batch.file_record where tenant_id in (?,?)";
    try {
      Long fileId = jdbc.queryForObject(
          insert,
          Long.class,
          tenant,
          "file-1",
          "one.dat",
          "one",
          LocalDate.of(2026, 10, 30),
          trace);
      jdbc.queryForObject(
          insert,
          Long.class,
          otherTenant,
          "file-2",
          "two.dat",
          "two",
          LocalDate.of(2026, 10, 30),
          trace);
      assertThat(mapper.selectWorkflowDatasets(tenant, null, trace))
          .extracting(row -> row.fileId())
          .containsExactly(fileId);
      assertThat(mapper.selectWorkflowDatasets(tenant, null, null)).isEmpty();
      assertThat(mapper.selectWorkflowDatasets(tenant, null, "")).isEmpty();
      assertThat(mapper.selectWorkflowDatasets(tenant, Long.MAX_VALUE, null)).isEmpty();
      assertThat(mapper.selectWorkflowDatasets(tenant, Long.MAX_VALUE, trace))
          .extracting(row -> row.fileId())
          .containsExactly(fileId);
    } finally {
      jdbc.update(delete, tenant, otherTenant);
    }
  }
}
