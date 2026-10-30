package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowInsertParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.param.ResourceQueueInsertParam;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** 真实 HTTP 和提交后的 PostgreSQL 行验证固定写入参数；包含主键回填与局部更新，不直接写业务状态。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("窗口与队列具名写入：主键、完整字段、默认值及局部更新保持原语义")
class ConsoleWindowQueueTypedWriteIntegrationTest extends AbstractMutationIntegrationTest {

  private final BatchWindowMapper windows;
  private final ResourceQueueMapper queues;

  @Autowired
  ConsoleWindowQueueTypedWriteIntegrationTest(
      BatchWindowMapper windows, ResourceQueueMapper queues) {
    this.windows = windows;
    this.queues = queues;
  }

  @Test
  @DisplayName("窗口Mapper在空策略及可空布尔输入时保留SQL默认分支并回填主键")
  void shouldPreserveWindowSqlDefaults_andGeneratedId() {
    BatchWindowInsertParam input = BatchWindowInsertParam.builder()
        .tenantId(tenant())
        .windowCode("window_defaults_" + Long.toUnsignedString(System.nanoTime()))
        .windowName("SQL默认窗口")
        .timezone("UTC")
        .startTime("01:00:00")
        .endTime("02:00:00")
        .endStrategy("")
        .outOfWindowAction("")
        .allowCrossDay(null)
        .enabled(null)
        .description(null)
        .build();
    assertThat(input.getId()).isNull();
    assertThat(windows.insert(input)).isEqualTo(1);
    assertThat(input.getId()).isPositive();
    assertThat(window(input.getId()))
        .containsEntry("end_strategy", "FINISH_RUNNING")
        .containsEntry("out_of_window_action", "WAIT")
        .containsEntry("allow_cross_day", false)
        .containsEntry("enabled", true)
        .containsEntry("description", null);
  }

  @Test
  @DisplayName("队列Mapper在空策略和可空配额/布尔输入时保留SQL默认分支并回填主键")
  void shouldPreserveQueueSqlDefaults_andGeneratedId() {
    ResourceQueueInsertParam input = ResourceQueueInsertParam.builder()
        .tenantId(tenant())
        .queueCode("queue_defaults_" + Long.toUnsignedString(System.nanoTime()))
        .queueName("SQL默认队列")
        .queueType("MIXED")
        .priorityPolicy("")
        .maxRunningJobs(null)
        .maxRunningPartitions(null)
        .maxQps(null)
        .fairShareWeight(null)
        .enabled(null)
        .workerGroup(null)
        .resourceTag(null)
        .description(null)
        .build();
    assertThat(input.getId()).isNull();
    assertThat(queues.insert(input)).isEqualTo(1);
    assertThat(input.getId()).isPositive();
    assertThat(queue(input.getId()))
        .containsEntry("max_running_jobs", 0)
        .containsEntry("max_running_partitions", 0)
        .containsEntry("max_qps", 0)
        .containsEntry("priority_policy", "FIFO")
        .containsEntry("fair_share_weight", 1)
        .containsEntry("enabled", true)
        .containsEntry("worker_group", null)
        .containsEntry("resource_tag", null)
        .containsEntry("description", null);
  }

  @Test
  @DisplayName("窗口创建与完整更新绑定全部字段，局部更新仅覆盖显式值并保留主键和编码")
  void shouldPreserveWindowFields_andPartialUpdateSemantics() {
    String tenant = tenant();
    String code = "typed_window_" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> create = Map.ofEntries(
        Map.entry("tenantId", tenant),
        Map.entry("windowCode", code),
        Map.entry("windowName", "原窗口"),
        Map.entry("timezone", "Asia/Shanghai"),
        Map.entry("startTime", "01:02:03"),
        Map.entry("endTime", "04:05:06"),
        Map.entry("endStrategy", "CONTINUE"),
        Map.entry("outOfWindowAction", "WAIT"),
        Map.entry("allowCrossDay", false),
        Map.entry("enabled", true),
        Map.entry("description", "原说明"));
    long id = create("/api/console/batch-windows", create);
    assertThat(id).isPositive();
    assertThat(window(id))
        .containsEntry("id", id)
        .containsEntry("tenant_id", tenant)
        .containsEntry("window_code", code)
        .containsEntry("window_name", "原窗口")
        .containsEntry("timezone", "Asia/Shanghai")
        .containsEntry("start_time", "01:02:03")
        .containsEntry("end_time", "04:05:06")
        .containsEntry("end_strategy", "CONTINUE")
        .containsEntry("out_of_window_action", "WAIT")
        .containsEntry("allow_cross_day", false)
        .containsEntry("enabled", true)
        .containsEntry("description", "原说明");
    Map<String, Object> update = Map.ofEntries(
        Map.entry("tenantId", tenant), Map.entry("windowName", "新窗口"),
        Map.entry("timezone", "UTC"), Map.entry("startTime", "22:00:00"),
        Map.entry("endTime", "02:00:00"), Map.entry("endStrategy", "STOP"),
        Map.entry("outOfWindowAction", "FAIL"), Map.entry("allowCrossDay", true),
        Map.entry("enabled", false), Map.entry("description", "新说明"));
    update("/api/console/batch-windows/" + id, update);
    Map<String, Object> complete = window(id);
    assertThat(complete)
        .containsEntry("id", id)
        .containsEntry("tenant_id", tenant)
        .containsEntry("window_code", code)
        .containsEntry("window_name", "新窗口")
        .containsEntry("timezone", "UTC")
        .containsEntry("start_time", "22:00:00")
        .containsEntry("end_time", "02:00:00")
        .containsEntry("end_strategy", "STOP")
        .containsEntry("out_of_window_action", "FAIL")
        .containsEntry("allow_cross_day", true)
        .containsEntry("enabled", false)
        .containsEntry("description", "新说明");

    update(
        "/api/console/batch-windows/" + id,
        Map.of("tenantId", tenant, "enabled", true, "description", ""));
    complete.put("enabled", true);
    complete.put("description", "");
    assertThat(window(id)).containsExactlyInAnyOrderEntriesOf(complete);
  }

  @Test
  @DisplayName("窗口默认策略和可空说明保持不变，显式false可更新且其他租户不能按主键改写")
  void shouldPreserveWindowDefaults_andTenantPredicate() {
    String tenant = tenant();
    String code = "default_window_" + Long.toUnsignedString(System.nanoTime());
    long id = create(
        "/api/console/batch-windows",
        Map.of(
            "tenantId",
            tenant,
            "windowCode",
            code,
            "windowName",
            "默认窗口",
            "timezone",
            "UTC",
            "startTime",
            "01:00:00",
            "endTime",
            "02:00:00"));
    assertThat(window(id))
        .containsEntry("end_strategy", "FINISH_RUNNING")
        .containsEntry("out_of_window_action", "WAIT")
        .containsEntry("allow_cross_day", false)
        .containsEntry("enabled", true)
        .containsEntry("description", null);
    update(
        "/api/console/batch-windows/" + id,
        Map.of("tenantId", tenant, "enabled", false, "allowCrossDay", false));
    Map<String, Object> before = window(id);
    assertThat(before)
        .containsEntry("enabled", false)
        .containsEntry("allow_cross_day", false)
        .containsEntry("window_name", "默认窗口")
        .containsEntry("timezone", "UTC")
        .containsEntry("start_time", "01:00:00")
        .containsEntry("end_time", "02:00:00");
    rejectCrossTenantUpdate("/api/console/batch-windows/" + id, tenant + "-other");
    assertThat(window(id)).containsExactlyInAnyOrderEntriesOf(before);
  }

  @Test
  @DisplayName("队列创建回填真实主键且保留规范引用，完整更新与局部零值/false更新均正确")
  void shouldPreserveQueueFields_andExplicitZeroUpdates() {
    String tenant = tenant();
    String suffix = Long.toUnsignedString(System.nanoTime());
    String suppliedCode = "Typed-Queue-" + suffix;
    String storedCode = "typed_queue_" + suffix;
    Map<String, Object> create = Map.ofEntries(
        Map.entry("tenantId", tenant),
        Map.entry("queueCode", suppliedCode),
        Map.entry("queueName", "原队列"),
        Map.entry("queueType", "MIXED"),
        Map.entry("maxRunningJobs", 4),
        Map.entry("maxRunningPartitions", 5),
        Map.entry("maxQps", 6),
        Map.entry("workerGroup", "group-a"),
        Map.entry("resourceTag", "tag-a"),
        Map.entry("priorityPolicy", "PRIORITY"),
        Map.entry("fairShareWeight", 7),
        Map.entry("enabled", false),
        Map.entry("description", "原说明"));
    long id = create("/api/console/queues", create);
    assertThat(id).isPositive();
    assertThat(queue(id))
        .containsEntry("id", id)
        .containsEntry("tenant_id", tenant)
        .containsEntry("queue_code", storedCode)
        .containsEntry("queue_name", "原队列")
        .containsEntry("queue_type", "MIXED")
        .containsEntry("max_running_jobs", 4)
        .containsEntry("max_running_partitions", 5)
        .containsEntry("max_qps", 6)
        .containsEntry("worker_group", "group-a")
        .containsEntry("resource_tag", "tag-a")
        .containsEntry("priority_policy", "PRIORITY")
        .containsEntry("fair_share_weight", 7)
        .containsEntry("enabled", false)
        .containsEntry("description", "原说明");
    Map<String, Object> update = Map.ofEntries(
        Map.entry("tenantId", tenant), Map.entry("queueName", "新队列"),
        Map.entry("queueType", "DISPATCH"), Map.entry("maxRunningJobs", 10),
        Map.entry("maxRunningPartitions", 11), Map.entry("maxQps", 12),
        Map.entry("workerGroup", "group-b"), Map.entry("resourceTag", "tag-b"),
        Map.entry("priorityPolicy", "FAIR_SHARE"), Map.entry("fairShareWeight", 3),
        Map.entry("enabled", true), Map.entry("description", "新说明"));
    update("/api/console/queues/" + id, update);
    Map<String, Object> complete = queue(id);
    assertThat(complete)
        .containsEntry("id", id)
        .containsEntry("tenant_id", tenant)
        .containsEntry("queue_code", storedCode)
        .containsEntry("queue_name", "新队列")
        .containsEntry("queue_type", "DISPATCH")
        .containsEntry("max_running_jobs", 10)
        .containsEntry("max_running_partitions", 11)
        .containsEntry("max_qps", 12)
        .containsEntry("worker_group", "group-b")
        .containsEntry("resource_tag", "tag-b")
        .containsEntry("priority_policy", "FAIR_SHARE")
        .containsEntry("fair_share_weight", 3)
        .containsEntry("enabled", true)
        .containsEntry("description", "新说明");

    update(
        "/api/console/queues/" + id,
        Map.of("tenantId", tenant, "maxQps", 0, "enabled", false, "description", ""));
    complete.put("max_qps", 0);
    complete.put("enabled", false);
    complete.put("description", "");
    assertThat(queue(id)).containsExactlyInAnyOrderEntriesOf(complete);
  }

  @Test
  @DisplayName("队列创建保留零配额、FIFO和权重默认值，局部改名与跨租户拒绝不改变其它字段")
  void shouldPreserveQueueDefaults_andTenantPredicate() {
    String tenant = tenant();
    long id = create(
        "/api/console/queues",
        Map.of(
            "tenantId",
            tenant,
            "queueCode",
            "default_queue_" + Long.toUnsignedString(System.nanoTime()),
            "queueName",
            "默认队列",
            "queueType",
            "MIXED"));
    assertThat(queue(id))
        .containsEntry("max_running_jobs", 0)
        .containsEntry("max_running_partitions", 0)
        .containsEntry("max_qps", 0)
        .containsEntry("priority_policy", "FIFO")
        .containsEntry("fair_share_weight", 1)
        .containsEntry("enabled", true)
        .containsEntry("worker_group", null)
        .containsEntry("resource_tag", null)
        .containsEntry("description", null);
    Map<String, Object> expected = queue(id);
    update("/api/console/queues/" + id, Map.of("tenantId", tenant, "queueName", "改名队列"));
    expected.put("queue_name", "改名队列");
    assertThat(queue(id)).containsExactlyInAnyOrderEntriesOf(expected);
    rejectCrossTenantUpdate("/api/console/queues/" + id, tenant + "-other");
    assertThat(queue(id)).containsExactlyInAnyOrderEntriesOf(expected);
  }

  private long create(String path, Map<String, Object> body) {
    EntityExchangeResult<String> result = client
        .post()
        .uri(path)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult();
    JsonNode response = JsonUtils.fromJson(result.getResponseBody(), JsonNode.class);
    assertThat(response).isNotNull();
    return response.path("data").path("id").longValue();
  }

  private void update(String path, Map<String, Object> body) {
    client
        .put()
        .uri(path)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk();
  }

  private void rejectCrossTenantUpdate(String path, String tenant) {
    client
        .put()
        .uri(path)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", tenant))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  private Map<String, Object> window(long id) {
    String sql = """
        select id, tenant_id, window_code, window_name, timezone,
          start_time::text as start_time, end_time::text as end_time,
          end_strategy, out_of_window_action, allow_cross_day, enabled, description
        from batch.batch_window where id = ?
        """;
    return jdbcTemplate.queryForMap(sql, id);
  }

  private Map<String, Object> queue(long id) {
    String sql = """
        select id, tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
          max_running_partitions, max_qps, worker_group, resource_tag,
          priority_policy, fair_share_weight, enabled, description
        from batch.resource_queue where id = ?
        """;
    return jdbcTemplate.queryForMap(sql, id);
  }

  private String tenant() {
    return "typed-write-" + Long.toUnsignedString(System.nanoTime());
  }

  private String operationKey() {
    return "typed-write-" + Long.toUnsignedString(System.nanoTime());
  }
}
