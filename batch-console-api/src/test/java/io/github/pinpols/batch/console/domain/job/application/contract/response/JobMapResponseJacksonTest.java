package io.github.pinpols.batch.console.domain.job.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.ops.response.ConsoleInstanceActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleRetryFailedPartitionsResponse;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护:类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 覆盖三个易错形态:snake_case 键(batch
 * window)、条件字段 NON_NULL 省略、嵌套 list。
 */
@DisplayName("任务域响应记录序列化契约: 键名形态, 空列省略与嵌套列表保真")
class JobMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  @DisplayName("批量窗口行序列化后保持历史蛇形键名, 未提供的列不输出显式空值")
  void shouldSerializeSnakeCaseKeys_whenBatchWindowRowMapped() throws Exception {
    // mapper `select *` 返回 snake_case 键 + TIME/timestamptz 列。
    Map<String, Object> row = Map.of(
        "id",
        5L,
        "tenant_id",
        "ta",
        "window_code",
        "nightly",
        "window_name",
        "夜间窗口",
        "timezone",
        "Asia/Shanghai",
        "start_time",
        Time.valueOf("02:00:00"),
        "end_time",
        Time.valueOf("04:00:00"),
        "end_strategy",
        "FINISH_RUNNING",
        "allow_cross_day",
        false,
        "enabled",
        true);

    String json = mapper.writeValueAsString(ConsoleBatchWindowResponse.from(row));
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});

    assertThat(back)
        .containsEntry("window_code", "nightly")
        .containsEntry("tenant_id", "ta")
        .containsEntry("start_time", "02:00")
        .containsEntry("end_strategy", "FINISH_RUNNING")
        .containsEntry("allow_cross_day", false);
    // 绝不能出现 camelCase 变体(FE 历史消费 snake_case)。
    assertThat(back).doesNotContainKey("windowCode").doesNotContainKey("tenantId");
    // MyBatis map 行省略 null 列 —— row 未含的列(description/out_of_window_action/created_at
    // /updated_at)必须省略键,不能序列化成显式 null(键集漂移)。
    assertThat(back)
        .doesNotContainKey("description")
        .doesNotContainKey("out_of_window_action")
        .doesNotContainKey("created_at")
        .doesNotContainKey("updated_at");
  }

  @Test
  @DisplayName("取消请求任务数为空时省略该键, 有值时按原值输出")
  void shouldOmitCancelRequestedTasks_whenCountNull() throws Exception {
    // terminate/pause/resume 无 cancelRequestedTasks —— 历史 Map 不含该键,NON_NULL 必须省略。
    String terminate = mapper.writeValueAsString(ConsoleInstanceActionResponse.from(
        Map.of("id", 9L, "instanceNo", "INS-9", "status", "TERMINATED")));
    Map<String, Object> terminateBack = mapper.readValue(terminate, new TypeReference<>() {});
    assertThat(terminateBack).containsOnlyKeys("id", "instanceNo", "status");

    // cancel RUNNING 实例时携带该键。
    String cancel = mapper.writeValueAsString(ConsoleInstanceActionResponse.from(Map.of(
        "id", 9L, "instanceNo", "INS-9", "status", "CANCEL_REQUESTED", "cancelRequestedTasks", 3)));
    Map<String, Object> cancelBack = mapper.readValue(cancel, new TypeReference<>() {});
    assertThat(cancelBack).containsEntry("cancelRequestedTasks", 3);
  }

  @Test
  @DisplayName("重试失败分区的结果保留嵌套分区编号列表及其顺序")
  void shouldPreserveNestedPartitionIds_whenRetryFailedPartitions() throws Exception {
    Map<String, Object> row = Map.of(
        "id",
        12L,
        "instanceNo",
        "INS-12",
        "requested",
        2,
        "retried",
        1,
        "conflicts",
        1,
        "partitionIds",
        List.of(101L, 102L));

    String json = mapper.writeValueAsString(ConsoleRetryFailedPartitionsResponse.from(row));
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});

    assertThat(back)
        .containsKeys("id", "instanceNo", "requested", "retried", "conflicts", "partitionIds");
    assertThat(back.get("partitionIds")).asInstanceOf(LIST).containsExactly(101, 102);
  }

  @Test
  @DisplayName("日历行映射后时间戳统一为瞬时点, 且键集只含已提供的列")
  void shouldNormalizeTimestamp_whenCalendarRowMapped() throws Exception {
    Instant updatedAt = Instant.parse("2026-07-11T04:00:00Z");
    ConsoleCalendarResponse response = ConsoleCalendarResponse.from(Map.of(
        "id",
        1L,
        "tenantId",
        "ta",
        "calendarCode",
        "cn",
        "enabled",
        true,
        "updatedAt",
        Timestamp.from(updatedAt)));
    assertThat(response.calendarCode()).isEqualTo("cn");
    assertThat(response.updatedAt()).isEqualTo(updatedAt);

    // MyBatis map 行省略 null 列 —— row 未含的列(calendarName/description/createdAt 等)
    // 序列化时必须省略键,不能出现显式 null(键集漂移)。
    String json = mapper.writeValueAsString(response);
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});
    assertThat(back).containsOnlyKeys("id", "tenantId", "calendarCode", "enabled", "updatedAt");
  }

  @Test
  @DisplayName("节假日行只序列化已提供的列, 空列不产生显式空键")
  void shouldOmitNullColumns_whenHolidayRowMapped() throws Exception {
    ConsoleHolidayResponse response = ConsoleHolidayResponse.from(Map.of(
        "id", 5L, "calendarId", 3L, "bizDate", Date.valueOf("2026-05-20"), "dayType", "HOLIDAY"));

    String json = mapper.writeValueAsString(response);
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});
    assertThat(back).containsOnlyKeys("id", "calendarId", "bizDate", "dayType");
  }
}
