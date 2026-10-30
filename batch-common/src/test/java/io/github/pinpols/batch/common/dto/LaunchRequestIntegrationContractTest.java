package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workflow 与每日连续性启动请求: 持久化和恢复保留两侧契约")
class LaunchRequestIntegrationContractTest {

  @Test
  @DisplayName("事件驱动 Workflow 等待后恢复: 目标、日期、事件和依赖证据均保留")
  void shouldPreserveBothContracts_whenRestoringWorkflowEventLaunch() {
    String json = """
        {
          "tenantId": "tenant-a",
          "workflowDefinitionId": 42,
          "bizDate": "2026-10-13",
          "triggerType": "EVENT",
          "requestId": "event-request-1",
          "externalEventCode": "CLOSE_READY",
          "dataIntervalStart": "2026-10-12T16:00:00Z",
          "dataIntervalEnd": "2026-10-13T16:00:00Z",
          "replaySessionId": 9,
          "dryRun": false,
          "dependencyResultSnapshot": [{
            "upstreamJobCode": "IMPORT_A",
            "bizDateAlignment": "SAME_DAY",
            "upstreamBizDate": "2026-10-13",
            "businessKey": "input-a",
            "versionNo": 3,
            "resultVersionId": 123,
            "sourceJobInstanceId": 456,
            "effectiveAt": "2026-10-13T01:00:00Z"
          }]
        }
        """;

    LaunchRequest restored = JsonUtils.fromJson(json, LaunchRequest.class);
    LaunchRequest replayed = restored.toBuilder().traceId("recovered-trace").build();
    LaunchRequest roundTrip = JsonUtils.fromJson(JsonUtils.toJson(replayed), LaunchRequest.class);

    assertThat(roundTrip.workflowDefinitionId()).isEqualTo(42L);
    assertThat(roundTrip.jobCode()).isNull();
    assertThat(roundTrip.externalEventCode()).isEqualTo("CLOSE_READY");
    assertThat(roundTrip.bizDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(roundTrip.dataIntervalStart()).isEqualTo(Instant.parse("2026-10-12T16:00:00Z"));
    assertThat(roundTrip.dataIntervalEnd()).isEqualTo(Instant.parse("2026-10-13T16:00:00Z"));
    assertThat(roundTrip.replaySessionId()).isEqualTo(9L);
    assertThat(roundTrip.dependencyResultSnapshot()).singleElement().satisfies(result -> {
      assertThat(result.resultVersionId()).isEqualTo(123L);
      assertThat(result.sourceJobInstanceId()).isEqualTo(456L);
    });
  }

  @Test
  @DisplayName("旧 Job 请求: 缺少新增字段时保持可恢复并保留未知值")
  void shouldKeepNewFieldsUnknown_whenReadingLegacyJobLaunch() {
    String json = """
        {"tenantId":"tenant-a","jobCode":"IMPORT_A","bizDate":"2026-10-13",
         "triggerType":"MANUAL","requestId":"legacy-request"}
        """;

    LaunchRequest request = JsonUtils.fromJson(json, LaunchRequest.class);

    assertThat(request.jobCode()).isEqualTo("IMPORT_A");
    assertThat(request.workflowDefinitionId()).isNull();
    assertThat(request.externalEventCode()).isNull();
    assertThat(request.dependencyResultSnapshot()).isNull();
  }
}
