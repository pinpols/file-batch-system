package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.application.contract.response.config.QuotaPolicyResponse;
import io.github.pinpols.batch.console.domain.file.query.FileArrivalGroupQuery;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeEventHub;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeRunQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowRunQuery;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("可读性工厂行为:具名入口保留原调用参数语义")
class ReadabilityFactoryBehaviorTest {

  @Test
  @DisplayName("配额全量查询仅省略筛选条件")
  void shouldDelegateListAllWithoutFilters() {
    ConsoleQuotaPolicyApplicationService service =
        mock(ConsoleQuotaPolicyApplicationService.class, CALLS_REAL_METHODS);
    PageResponse<QuotaPolicyResponse> expected = new PageResponse<>(0, 1, 100, List.of());
    doReturn(expected).when(service).list("t1", null, null, 1, 100);

    assertThat(service.listAll("t1", 1, 100)).isSameAs(expected);
  }

  @Test
  @DisplayName("实时全事件订阅仅省略类型与游标筛选")
  void shouldDelegateAllEventSubscriptionWithoutFilters() {
    ConsoleRealtimeEventHub hub = mock(ConsoleRealtimeEventHub.class, CALLS_REAL_METHODS);
    SseEmitter expected = new SseEmitter();
    doReturn(expected).when(hub).subscribe("t1", "ops", null, null, 25_000L);

    assertThat(hub.subscribeAllEvents("t1", "ops", 25_000L)).isSameAs(expected);
    verify(hub).subscribe("t1", "ops", null, null, 25_000L);
  }

  @Test
  @DisplayName("查询具名工厂只设置调用方可见的业务条件")
  void shouldBuildQueriesFromVisibleBusinessArguments() {
    PageRequest pageRequest = new PageRequest(1, 15);

    FileArrivalGroupQuery arrival =
        FileArrivalGroupQuery.ofState("t1", "group-a", "ARRIVED", pageRequest);
    WorkflowRunQuery run = WorkflowRunQuery.ofDefinition("t1", 7L);
    WorkflowNodeRunQuery nodeRun = WorkflowNodeRunQuery.ofWorkflowRun("t1", 9L);

    assertThat(arrival)
        .extracting(
            FileArrivalGroupQuery::tenantId,
            FileArrivalGroupQuery::fileGroupCode,
            FileArrivalGroupQuery::arrivalState,
            FileArrivalGroupQuery::pageRequest)
        .containsExactly("t1", "group-a", "ARRIVED", pageRequest);
    assertThat(arrival.fromTime()).isNull();
    assertThat(arrival.toTime()).isNull();
    assertThat(run.workflowDefinitionId()).isEqualTo(7L);
    assertThat(nodeRun.workflowRunId()).isEqualTo(9L);
  }
}
