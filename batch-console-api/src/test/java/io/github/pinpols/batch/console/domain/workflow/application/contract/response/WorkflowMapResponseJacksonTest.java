package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunSkipNodeResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：批次4 workflow 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。
 * 覆盖编排代理动作固定键、跳过节点固定键、pipeline 列表 snake_case 键与 NON_NULL 省略。
 */
@DisplayName("工作流类型化响应序列化: 键名历史兼容与空值省略契约")
class WorkflowMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }

  @Test
  @DisplayName("运行动作回包仅保留编号与状态两键, 且整型编号归一为数值 42")
  void shouldKeepIdAndStatusKeys_whenRunActionRoundTrips() throws Exception {
    // 编排 HTTP 回传 id 可能反序列化为 Integer；record longValue 归一为 Long。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 42);
    row.put("status", "TERMINATED");

    Map<String, Object> back = roundTrip(ConsoleWorkflowRunActionResponse.from(row));

    assertThat(back).containsOnlyKeys("id", "status");
    assertThat(back).containsEntry("id", 42).containsEntry("status", "TERMINATED");
  }

  @Test
  @DisplayName("跳过节点回包仅保留编号、节点编码与节点状态三键, 取值原样保留")
  void shouldKeepThreeKeys_whenSkipNodeRoundTrips() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 7);
    row.put("nodeCode", "N1");
    row.put("nodeStatus", "SKIPPED");

    Map<String, Object> back = roundTrip(ConsoleWorkflowRunSkipNodeResponse.from(row));

    assertThat(back).containsOnlyKeys("id", "nodeCode", "nodeStatus");
    assertThat(back).containsEntry("nodeCode", "N1").containsEntry("nodeStatus", "SKIPPED");
  }
}
