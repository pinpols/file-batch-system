package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeOperationService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("Replay Operation HTTP 响应只包含公开审计字段")
class ReplayOperationResponseContractTest {

  @Mock
  private BatchDayReplayService dailyReplay;

  @Mock
  private BatchDayReplayRangeOperationService rangeReplay;

  @ParameterizedTest
  @EnumSource(Action.class)
  @DisplayName("五个操作接口均保留客户端公开字段且排除冻结参数与内部指纹")
  void shouldReturnPublicProjection_whenOperationEndpointSucceeds(Action action) throws Exception {
    ReplayOperationEntity operation = ReplayOperationEntity.builder()
        .id(71L)
        .tenantId("tenant-a")
        .operationKey("operation-a")
        .operationType("BATCH_DAY_RANGE")
        .status("PENDING_APPROVAL")
        .requestedBy("operator-a")
        .reason("重放业务日")
        .totalCount(2)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(2)
        .scopePayload("private-scope-payload")
        .snapshotPayload("private-frozen-parameters")
        .requestHash("private-request-hash")
        .snapshotHash("private-snapshot-hash")
        .build();
    MockHttpServletRequestBuilder request = request(action, operation);
    BatchDayReplayController controller = new BatchDayReplayController(dailyReplay, rangeReplay);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();

    String body = result.getResponse().getContentAsString();
    JsonNode response = JsonUtils.fromJson(body, JsonNode.class);
    JsonNode data = action == Action.LIST ? response.path("data").get(0) : response.path("data");
    List<String> fields = new ArrayList<>();
    data.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "id",
            "tenantId",
            "operationKey",
            "operationType",
            "status",
            "calendarCode",
            "fromDate",
            "throughDate",
            "scope",
            "executionMode",
            "candidateSource",
            "resultPolicy",
            "configVersionPolicy",
            "configVersion",
            "reason",
            "requestedBy",
            "approvedBy",
            "totalCount",
            "succeededCount",
            "failedCount",
            "inFlightCount",
            "startedAt",
            "completedAt",
            "traceId",
            "createdAt",
            "updatedAt");
    long actualOperationId = data.path("id").asLong();
    int actualTotalCount = data.path("totalCount").asInt();
    assertThat(actualOperationId).isEqualTo(71L);
    assertThat(actualTotalCount).isEqualTo(2);
    assertThat(body)
        .doesNotContain(
            "snapshotPayload",
            "scopePayload",
            "requestHash",
            "snapshotHash",
            "private-frozen-parameters",
            "private-scope-payload",
            "private-request-hash",
            "private-snapshot-hash");
  }

  private MockHttpServletRequestBuilder request(Action action, ReplayOperationEntity operation) {
    String base = "/internal/orchestrator/batch-day-replay/operations";
    return switch (action) {
      case SUBMIT -> {
        when(rangeReplay.submit(any())).thenReturn(operation);
        String body = """
            {
              "tenantId": "tenant-a",
              "calendarCode": "CAL",
              "fromDate": "2026-10-20",
              "throughDate": "2026-10-21",
              "scope": "ALL",
              "reason": "重放业务日",
              "requestedBy": "operator-a",
              "autoApprove": false,
              "candidateSource": "EXISTING_INSTANCES"
            }
            """;
        yield post(base + "/date-range").contentType(MediaType.APPLICATION_JSON).content(body);
      }
      case LIST -> {
        when(rangeReplay.list("tenant-a", 50)).thenReturn(List.of(operation));
        yield get(base).param("tenantId", "tenant-a");
      }
      case DETAIL -> {
        when(rangeReplay.get("tenant-a", 71L)).thenReturn(operation);
        yield get(base + "/71").param("tenantId", "tenant-a");
      }
      case APPROVE -> {
        when(rangeReplay.approve("tenant-a", 71L, "approver-a")).thenReturn(operation);
        yield post(base + "/71/approve")
            .param("tenantId", "tenant-a")
            .param("approver", "approver-a");
      }
      case CANCEL -> {
        when(rangeReplay.cancel("tenant-a", 71L)).thenReturn(operation);
        yield post(base + "/71/cancel").param("tenantId", "tenant-a");
      }
    };
  }

  private enum Action {
    SUBMIT,
    LIST,
    DETAIL,
    APPROVE,
    CANCEL
  }
}
