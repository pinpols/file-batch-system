package io.github.pinpols.batch.console.domain.workflow.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：批次4 workflow 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。
 * 覆盖编排代理动作固定键、跳过节点固定键、pipeline 列表 snake_case 键与 NON_NULL 省略。
 */
class WorkflowMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }

  @Test
  void runActionKeepsIdAndStatus() throws Exception {
    // 编排 HTTP 回传 id 可能反序列化为 Integer；record longValue 归一为 Long。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 42);
    row.put("status", "TERMINATED");

    Map<String, Object> back = roundTrip(ConsoleWorkflowRunActionResponse.from(row));

    assertThat(back).containsOnlyKeys("id", "status");
    assertThat(back).containsEntry("id", 42).containsEntry("status", "TERMINATED");
  }

  @Test
  void skipNodeKeepsThreeKeys() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 7);
    row.put("nodeCode", "N1");
    row.put("nodeStatus", "SKIPPED");

    Map<String, Object> back = roundTrip(ConsoleWorkflowRunSkipNodeResponse.from(row));

    assertThat(back).containsOnlyKeys("id", "nodeCode", "nodeStatus");
    assertThat(back).containsEntry("nodeCode", "N1").containsEntry("nodeStatus", "SKIPPED");
  }

  @Test
  void pipelineListItemKeepsSnakeCaseKeysAndOmitsNullColumns() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 3L);
    row.put("tenant_id", "acme");
    row.put("job_code", "JOB_A");
    row.put("pipeline_name", "p1");
    row.put("pipeline_type", "IMPORT");
    row.put("biz_type", "daily");
    row.put("worker_group", "grp");
    row.put("version", 2);
    row.put("enabled", true);
    row.put("created_at", Instant.parse("2026-07-11T00:00:00Z"));
    row.put("updated_at", Instant.parse("2026-07-11T01:00:00Z"));

    Map<String, Object> back = roundTrip(ConsolePipelineDefinitionListItemResponse.from(row));

    assertThat(back)
        .containsKeys(
            "id",
            "tenant_id",
            "job_code",
            "pipeline_name",
            "pipeline_type",
            "biz_type",
            "worker_group",
            "version",
            "enabled",
            "created_at",
            "updated_at");
    assertThat(back).containsEntry("job_code", "JOB_A").containsEntry("version", 2);
    // description 未 put → NON_NULL 省略。
    assertThat(back).doesNotContainKey("description");
  }
}
