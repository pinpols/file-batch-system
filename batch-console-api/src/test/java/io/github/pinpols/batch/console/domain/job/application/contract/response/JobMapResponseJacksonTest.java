package io.github.pinpols.batch.console.domain.job.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayOperateResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleInstanceActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsolePartitionActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleRetryFailedPartitionsResponse;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarHolidayRow;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarQueryRow;
import java.time.Instant;
import java.time.LocalDate;
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
  @DisplayName("批量日和分区操作响应可按契约反序列化")
  void shouldDeserializeBatchDayAndPartitionActionResponses() throws Exception {
    var batchDay = mapper.readValue(
        "{\"batchDayId\":12,\"dayStatus\":\"OPEN\",\"frozen\":false,\"releasedLaunchCount\":3}",
        ConsoleBatchDayOperateResponse.class);
    var partition = mapper.readValue(
        "{\"id\":42,\"status\":\"CANCELLED\"}", ConsolePartitionActionResponse.class);
    assertThat(batchDay.batchDayId()).isEqualTo(12L);
    assertThat(batchDay.releasedLaunchCount()).isEqualTo(3);
    assertThat(partition.id()).isEqualTo(42L);
    assertThat(partition.status()).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("批量窗口行序列化后保持历史蛇形键名, 未提供的列不输出显式空值")
  void shouldSerializeSnakeCaseKeys_whenBatchWindowRowMapped() throws Exception {
    BatchWindowRow row = new BatchWindowRow(
        5L,
        "ta",
        "nightly",
        "夜间窗口",
        "Asia/Shanghai",
        "02:00",
        "04:00",
        "FINISH_RUNNING",
        null,
        false,
        true,
        null,
        null,
        null);

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
    // 可空列必须省略键，不能序列化成显式 null（键集漂移）。
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
    String terminate = mapper.writeValueAsString(mapper.readValue(
        "{\"id\":9,\"instanceNo\":\"INS-9\",\"status\":\"TERMINATED\"}",
        ConsoleInstanceActionResponse.class));
    Map<String, Object> terminateBack = mapper.readValue(terminate, new TypeReference<>() {});
    assertThat(terminateBack).containsOnlyKeys("id", "instanceNo", "status");

    // cancel RUNNING 实例时携带该键。
    String cancel = mapper.writeValueAsString(mapper.readValue(
        "{\"id\":9,\"instanceNo\":\"INS-9\",\"status\":\"CANCEL_REQUESTED\",\"cancelRequestedTasks\":3}",
        ConsoleInstanceActionResponse.class));
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

    var decoded = mapper.readValue(
        mapper.writeValueAsString(row), ConsoleRetryFailedPartitionsResponse.class);
    String json = mapper.writeValueAsString(decoded);
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});

    assertThat(back)
        .containsKeys("id", "instanceNo", "requested", "retried", "conflicts", "partitionIds");
    assertThat(back.get("partitionIds")).asInstanceOf(LIST).containsExactly(101, 102);
  }

  @Test
  @DisplayName("日历行映射后时间戳统一为瞬时点, 且键集只含已提供的列")
  void shouldNormalizeTimestamp_whenCalendarRowMapped() throws Exception {
    Instant updatedAt = Instant.parse("2026-07-11T04:00:00Z");
    CalendarQueryRow row = new CalendarQueryRow(
        1L, "ta", "cn", null, null, null, null, null, true, null, null, updatedAt);
    ConsoleCalendarResponse response = ConsoleCalendarResponse.from(row);
    assertThat(response.calendarCode()).isEqualTo("cn");
    assertThat(response.updatedAt()).isEqualTo(updatedAt);

    // 可空列序列化时必须省略键，不能出现显式 null（键集漂移）。
    String json = mapper.writeValueAsString(response);
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});
    assertThat(back).containsOnlyKeys("id", "tenantId", "calendarCode", "enabled", "updatedAt");
  }

  @Test
  @DisplayName("节假日行只序列化已提供的列, 空列不产生显式空键")
  void shouldOmitNullColumns_whenHolidayRowMapped() throws Exception {
    CalendarHolidayRow row = new CalendarHolidayRow(
        5L, 3L, LocalDate.of(2026, 5, 20), "HOLIDAY", null, null, null, null);
    ConsoleHolidayResponse response = ConsoleHolidayResponse.from(row);

    String json = mapper.writeValueAsString(response);
    Map<String, Object> back = mapper.readValue(json, new TypeReference<>() {});
    assertThat(back).containsOnlyKeys("id", "calendarId", "bizDate", "dayType");
  }
}
