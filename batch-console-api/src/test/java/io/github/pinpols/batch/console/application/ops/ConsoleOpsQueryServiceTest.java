package io.github.pinpols.batch.console.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.console.application.contract.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.WorkerRegistryQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayWindowQueryRequest;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.view.BatchDayScheduledOccurrenceView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayView;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.ops.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("控制台运维查询服务")
class ConsoleOpsQueryServiceTest {

  @Mock
  private WorkerRegistryMapper workerRegistryMapper;

  @Mock
  private BatchDayMapper batchDayMapper;

  @Mock
  private AlertEventMapper alertEventMapper;

  @Mock
  private ApprovalCommandMapper approvalCommandMapper;

  @Mock
  private LocalizedErrorRenderer localizedErrorRenderer;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  @Test
  @DisplayName("查询 Worker 列表时返回已持久化的执行器能力")
  void shouldIncludeWorkerTaskCapabilities_whenLoadingWorkerList() {
    WorkerRegistryEntity worker = new WorkerRegistryEntity();
    worker.setId(1L);
    worker.setTenantId("tenant-a");
    worker.setWorkerCode("atomic-1");
    worker.setWorkerGroup("atomic");
    worker.setStatus("ONLINE");
    worker.setCapabilityTagsJson("[\"atomic\"]");
    worker.setResourceTag("disk");
    worker.setTaskCapabilitiesJson("[{\"taskType\":\"file_sha256\",\"resourceKinds\":[\"DISK\"],"
        + "\"idempotent\":true,\"cancellable\":false,"
        + "\"recommendedTimeoutMillis\":300000}]");
    when(workerRegistryMapper.selectByQuery(any())).thenReturn(List.of(worker));
    when(workerRegistryMapper.countByQuery(any())).thenReturn(1L);
    ConsoleOpsQueryMappers mappers = new ConsoleOpsQueryMappers(
        null, null, null, null, null, null, workerRegistryMapper, null, null, null, null, null);
    TenantIdResolver tenantResolver = requestTenantId -> requestTenantId;
    ConsoleOpsQueryService service = new ConsoleOpsQueryService(
        tenantResolver, mappers, localizedErrorRenderer, timezoneProvider);
    WorkerRegistryQueryRequest request = new WorkerRegistryQueryRequest();
    request.setTenantId("tenant-a");

    var response = service.workers(request).items().getFirst();

    assertThat(response.capabilityTags()).isEqualTo("[\"atomic\"]");
    assertThat(response.resourceTag()).isEqualTo("disk");
    assertThat(response.taskCapabilities()).singleElement().satisfies(capability -> {
      assertThat(capability.taskType()).isEqualTo("file_sha256");
      assertThat(capability.resourceKinds()).containsExactly("DISK");
      assertThat(capability.recommendedTimeoutMillis()).isEqualTo(300_000L);
    });
  }

  @Test
  @DisplayName("告警详情和审批载荷只在查询响应中递归脱敏")
  void shouldMaskDynamicJsonInReadResponses() {
    AlertEventEntity alert = new AlertEventEntity();
    alert.setId(2L);
    alert.setTenantId("tenant-a");
    alert.setDetailJson("{\"reason\":\"failed\",\"auth\":{\"clientSecret\":\"raw\"}}");
    ApprovalCommandEntity approval = new ApprovalCommandEntity();
    approval.setId(3L);
    approval.setTenantId("tenant-a");
    approval.setPayloadJson("{\"action\":\"retry\",\"apiToken\":\"raw\"}");
    when(alertEventMapper.selectByQuery(any())).thenReturn(List.of(alert));
    when(alertEventMapper.countByQuery(any())).thenReturn(1L);
    when(approvalCommandMapper.selectByQuery(any())).thenReturn(List.of(approval));
    when(approvalCommandMapper.countByQuery(any())).thenReturn(1L);
    ConsoleOpsQueryMappers mappers = new ConsoleOpsQueryMappers(
        null,
        alertEventMapper,
        approvalCommandMapper,
        null,
        null,
        null,
        workerRegistryMapper,
        null,
        null,
        null,
        null,
        null);
    ConsoleOpsQueryService service = new ConsoleOpsQueryService(
        requestTenantId -> requestTenantId, mappers, localizedErrorRenderer, timezoneProvider);
    AlertEventQueryRequest alertRequest = new AlertEventQueryRequest();
    alertRequest.setTenantId("tenant-a");
    ApprovalCommandQueryRequest approvalRequest = new ApprovalCommandQueryRequest();
    approvalRequest.setTenantId("tenant-a");

    var alertResponse = service.alertEvents(alertRequest).items().getFirst();
    var approvalResponse = service.approvals(approvalRequest).items().getFirst();

    assertThat(alertResponse.detailJson())
        .contains("&quot;reason&quot;:&quot;failed&quot;")
        .contains("&quot;clientSecret&quot;:&quot;****&quot;")
        .doesNotContain("raw");
    assertThat(approvalResponse.payloadJson())
        .contains("\"action\":\"retry\"")
        .contains("\"apiToken\":\"****\"")
        .doesNotContain("raw");
  }

  @Test
  @DisplayName("批次日窗口响应包括 occurrence 拒绝原因和请求关联")
  void shouldExposeScheduledOccurrencesInBatchDayWindow() {
    LocalDate bizDate = LocalDate.of(2026, 10, 15);
    BatchDayView row = new BatchDayView(
        bizDate, "SETTLED", null, null, null, null, 0, 0, 0L, 0L, 0L, 0L);
    when(batchDayMapper.selectWindow("tenant-a", "CALENDAR", bizDate)).thenReturn(row);
    when(batchDayMapper.selectJobSummaries("tenant-a", "CALENDAR", bizDate)).thenReturn(List.of());
    BatchDayScheduledOccurrenceView occurrenceRow = new BatchDayScheduledOccurrenceView(
        42L,
        "daily-settlement",
        null,
        3,
        Instant.parse("2026-10-15T02:00:00Z"),
        bizDate,
        "CLOSED_DAY_REJECTED",
        null,
        "explicit CATCH_UP or RERUN request required");
    when(batchDayMapper.selectScheduledOccurrences("tenant-a", "CALENDAR", bizDate))
        .thenReturn(List.of(occurrenceRow));
    ConsoleOpsQueryMappers mappers = new ConsoleOpsQueryMappers(
        null,
        null,
        null,
        null,
        null,
        null,
        workerRegistryMapper,
        null,
        null,
        null,
        batchDayMapper,
        null);
    ConsoleOpsQueryService service = new ConsoleOpsQueryService(
        requestTenantId -> requestTenantId, mappers, localizedErrorRenderer, timezoneProvider);
    BatchDayWindowQueryRequest request = new BatchDayWindowQueryRequest();
    request.setTenantId("tenant-a");
    request.setCalendarCode("CALENDAR");

    var occurrence =
        service.batchDayWindow("2026-10-15", request).scheduledOccurrences().getFirst();

    assertThat(occurrence.workflowDefinitionId()).isEqualTo(42L);
    assertThat(occurrence.workflowCode()).isEqualTo("daily-settlement");
    assertThat(occurrence.occurrenceStatus()).isEqualTo("CLOSED_DAY_REJECTED");
    assertThat(occurrence.requestId()).isNull();
    assertThat(occurrence.lastRejectionReason())
        .isEqualTo("explicit CATCH_UP or RERUN request required");
  }
}
