package io.github.pinpols.batch.orchestrator.application.service.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.FileRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.JobInstance;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.ResultVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("血缘固定投影保持 snake_case、空值省略与动态 metadata JSON 契约")
class LineageEvidenceResponseContractTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  @DisplayName("数据库证据行使用既有 snake_case，缺失数据库列不新增 null 字段")
  void shouldPreserveKeysAndOmissions_whenDatabaseRowIsSerialized() {
    JobInstance row = JobInstance.builder()
        .id(11L)
        .tenantId("ta")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.OCTOBER, 7))
        .instanceStatus("SUCCESS")
        .createdAt(Instant.parse("2026-10-07T00:00:00Z"))
        .build();
    String json = mapper.writeValueAsString(row);
    assertThat(json)
        .contains(
            "\"tenant_id\":\"ta\"",
            "\"job_code\":\"JOB_A\"",
            "\"biz_date\":\"2026-10-07\"",
            "\"instance_status\":\"SUCCESS\"")
        .doesNotContain("\"tenantId\"", "\"finished_at\"", "\"trace_id\"");
    assertThat(mapper.readValue(json, JobInstance.class)).isEqualTo(row);
  }

  @Test
  @DisplayName("结果版本继续使用 camelCase，并保留为空的版本字段")
  void shouldPreserveCamelCaseAndNulls_whenResultVersionIsSerialized() {
    ResultVersion row = ResultVersion.builder().id(12L).tenantId("ta").build();
    String json = mapper.writeValueAsString(row);
    assertThat(json)
        .contains("\"tenantId\":\"ta\"", "\"payloadRef\":null")
        .doesNotContain("\"tenant_id\"");
    assertThat(mapper.readValue(json, ResultVersion.class)).isEqualTo(row);
  }

  @Test
  @DisplayName("文件 metadata 保持用户动态 JSON，不输出 JDBC 驱动封装对象")
  void shouldKeepDynamicJson_whenFileMetadataIsSerialized() {
    FileRecord row =
        FileRecord.builder().id(13L).metadataJson(Map.of("recordCount", 42)).build();
    String json = mapper.writeValueAsString(row);
    assertThat(json).contains("\"metadata_json\":{\"recordCount\":42}");
    assertThat(mapper.readValue(json, FileRecord.class).metadataJson())
        .containsEntry("recordCount", 42);
  }
}
