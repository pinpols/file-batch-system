package io.github.pinpols.batch.console.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobStepInstanceResponse;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobPartitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.console.domain.job.support.ConsoleJobQueryMappers;
import io.github.pinpols.batch.console.domain.job.view.JobStepInstanceView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("作业步骤查询: 内嵌任务载荷凭据的响应遮蔽")
class ConsoleJobStepInstanceQueryTest {

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobExecutionLogMapper jobExecutionLogMapper;

  @Mock
  private LocalizedErrorRenderer localizedErrorRenderer;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  @Test
  @DisplayName("步骤结果摘要中的 JSON 字符串任务载荷不回显密码")
  void shouldMaskCredentialsInEmbeddedTaskPayload() throws Exception {
    String taskPayload = JsonUtils.toJson(Map.of("password", "probe-secret", "region", "west"));
    JobStepInstanceView entity = new JobStepInstanceView();
    entity.setId(7L);
    entity.setResultSummary(
        JsonUtils.toJson(Map.of("taskPayload", taskPayload, "outputs", Map.of("rows", 1))));
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobStepInstanceMapper.selectById("tenant-a", 7L)).thenReturn(entity);
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    ConsoleJobQueryService service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);

    ConsoleJobStepInstanceResponse response = service.jobStepInstance("tenant-a", 7L);

    JsonNode summary = JsonUtils.newDefaultMapper().readTree(response.resultSummary());
    JsonNode returnedPayload =
        JsonUtils.newDefaultMapper().readTree(summary.path("taskPayload").asText());
    assertThat(returnedPayload.path("password").asText()).isEqualTo("****");
    assertThat(returnedPayload.path("region").asText()).isEqualTo("west");
    assertThat(summary.path("outputs").path("rows").asInt()).isEqualTo(1);
    assertThat(response.resultSummary()).doesNotContain("probe-secret");
  }

  @Test
  @DisplayName("原子 HTTP 成功输出中的 JSON 响应正文不回显凭据")
  void shouldMaskCredentialsInAtomicHttpResponseBody() throws Exception {
    JobStepInstanceView entity = new JobStepInstanceView();
    entity.setId(12L);
    entity.setResultSummary(JsonUtils.toJson(Map.of(
        "outputs",
        Map.of(
            "statusCode", 200, "responseBody", "{\"accessToken\":\"probe-secret\",\"rows\":3}"))));
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobStepInstanceMapper.selectById("tenant-a", 12L)).thenReturn(entity);
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    ConsoleJobQueryService service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);

    ConsoleJobStepInstanceResponse response = service.jobStepInstance("tenant-a", 12L);
    JsonNode summary = JsonUtils.newDefaultMapper().readTree(response.resultSummary());
    JsonNode body = JsonUtils.newDefaultMapper()
        .readTree(summary.path("outputs").path("responseBody").asText());
    assertThat(body.path("accessToken").asText()).isEqualTo("****");
    assertThat(body.path("rows").asInt()).isEqualTo(3);
    assertThat(response.resultSummary()).doesNotContain("probe-secret");
  }

  @Test
  @DisplayName("损坏的 JSON 响应正文遮蔽，普通正文保持原样")
  void shouldMaskMalformedAtomicHttpJsonAndPreservePlainResponseBody() throws Exception {
    JobStepInstanceView malformed = new JobStepInstanceView();
    malformed.setResultSummary(JsonUtils.toJson(
        Map.of("outputs", Map.of("responseBody", "{\"accessToken\":\"probe-secret\","))));
    JobStepInstanceView plain = new JobStepInstanceView();
    plain.setResultSummary(JsonUtils.toJson(Map.of("outputs", Map.of("responseBody", "accepted"))));
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobStepInstanceMapper.selectById("tenant-a", 13L)).thenReturn(malformed);
    when(jobStepInstanceMapper.selectById("tenant-a", 14L)).thenReturn(plain);
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    ConsoleJobQueryService service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);

    JsonNode masked = JsonUtils.newDefaultMapper()
        .readTree(service.jobStepInstance("tenant-a", 13L).resultSummary());
    JsonNode unchanged = JsonUtils.newDefaultMapper()
        .readTree(service.jobStepInstance("tenant-a", 14L).resultSummary());
    assertThat(masked.path("outputs").path("responseBody").asText()).isEqualTo("****");
    assertThat(unchanged.path("outputs").path("responseBody").asText()).isEqualTo("accepted");
  }

  @Test
  @DisplayName("内嵌任务载荷损坏时遮蔽整个字段而保留其它结果")
  void shouldMaskMalformedEmbeddedTaskPayload() throws Exception {
    JobStepInstanceView entity = new JobStepInstanceView();
    entity.setId(8L);
    entity.setResultSummary(JsonUtils.toJson(
        Map.of("taskPayload", "{\"password\":\"probe-secret\",", "outputs", Map.of("rows", 2))));
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobStepInstanceMapper.selectById("tenant-a", 8L)).thenReturn(entity);
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    ConsoleJobQueryService service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);

    ConsoleJobStepInstanceResponse response = service.jobStepInstance("tenant-a", 8L);

    JsonNode summary = JsonUtils.newDefaultMapper().readTree(response.resultSummary());
    assertThat(summary.path("taskPayload").asText()).isEqualTo("****");
    assertThat(summary.path("outputs").path("rows").asInt()).isEqualTo(2);
    assertThat(response.resultSummary()).doesNotContain("probe-secret");
  }

  @Test
  @DisplayName("Worker 结果摘要中的内嵌 JSON 凭据不回显")
  void shouldMaskCredentialsInEmbeddedWorkerResultSummary() throws Exception {
    String workerSummary = JsonUtils.toJson(Map.of("accessToken", "worker-secret", "rows", 3));
    JobStepInstanceView entity = new JobStepInstanceView();
    entity.setId(9L);
    entity.setResultSummary(
        JsonUtils.toJson(Map.of("resultSummary", workerSummary, "outputs", Map.of("rows", 3))));
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobStepInstanceMapper.selectById("tenant-a", 9L)).thenReturn(entity);
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    ConsoleJobQueryService service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);

    ConsoleJobStepInstanceResponse response = service.jobStepInstance("tenant-a", 9L);

    JsonNode summary = JsonUtils.newDefaultMapper().readTree(response.resultSummary());
    JsonNode returnedWorkerSummary =
        JsonUtils.newDefaultMapper().readTree(summary.path("resultSummary").asText());
    assertThat(returnedWorkerSummary.path("accessToken").asText()).isEqualTo("****");
    assertThat(returnedWorkerSummary.path("rows").asInt()).isEqualTo(3);
    assertThat(summary.path("outputs").path("rows").asInt()).isEqualTo(3);
    assertThat(response.resultSummary()).doesNotContain("worker-secret");
  }

  @Test
  @DisplayName("损坏的 Worker JSON 摘要关闭回显，普通文字摘要保持原样")
  void shouldMaskMalformedWorkerJsonAndPreservePlainSummary() throws Exception {
    JobStepInstanceView malformed = new JobStepInstanceView();
    malformed.setId(10L);
    malformed.setResultSummary(JsonUtils.toJson(Map.of(
        "resultSummary", "{\"accessToken\":\"worker-secret\",", "outputs", Map.of("rows", 4))));
    JobStepInstanceView plain = new JobStepInstanceView();
    plain.setId(11L);
    plain.setResultSummary(JsonUtils.toJson(
        Map.of("resultSummary", "processed 4 rows", "outputs", Map.of("rows", 4))));
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(jobStepInstanceMapper.selectById("tenant-a", 10L)).thenReturn(malformed);
    when(jobStepInstanceMapper.selectById("tenant-a", 11L)).thenReturn(plain);
    ConsoleJobQueryMappers mappers = new ConsoleJobQueryMappers(
        jobDefinitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        jobPartitionMapper,
        jobExecutionLogMapper);
    ConsoleJobQueryService service =
        new ConsoleJobQueryService(tenantGuard, mappers, localizedErrorRenderer, timezoneProvider);

    ConsoleJobStepInstanceResponse malformedResponse = service.jobStepInstance("tenant-a", 10L);
    ConsoleJobStepInstanceResponse plainResponse = service.jobStepInstance("tenant-a", 11L);

    JsonNode malformedSummary =
        JsonUtils.newDefaultMapper().readTree(malformedResponse.resultSummary());
    JsonNode plainSummary = JsonUtils.newDefaultMapper().readTree(plainResponse.resultSummary());
    assertThat(malformedSummary.path("resultSummary").asText()).isEqualTo("****");
    assertThat(malformedSummary.path("outputs").path("rows").asInt()).isEqualTo(4);
    assertThat(malformedResponse.resultSummary()).doesNotContain("worker-secret");
    assertThat(plainSummary.path("resultSummary").asText()).isEqualTo("processed 4 rows");
  }
}
