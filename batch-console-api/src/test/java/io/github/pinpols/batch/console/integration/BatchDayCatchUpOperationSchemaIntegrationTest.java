package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.infrastructure.BatchDayCatchUpOperationStore;
import io.github.pinpols.batch.console.domain.job.infrastructure.BatchDayCatchUpReconciliation;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayCatchUpOperationMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchDayCatchUpOperationCreate;
import io.github.pinpols.batch.console.domain.job.param.BatchDayCatchUpOperationTransition;
import io.github.pinpols.batch.console.domain.job.view.BatchDayCatchUpOperation;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("批量日补跑恢复记录的数据库约束")
class BatchDayCatchUpOperationSchemaIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private BatchDayCatchUpOperationMapper operations;

  @Autowired
  private BatchDayCatchUpOperationStore operationStore;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private BatchDayCatchUpReconciliation reconciliationProxy;

  @Test
  @DisplayName("原批次接续只提交待处理审批并保留原申请人")
  void shouldContinueOnlyFrozenPendingApproval() {
    String tenant = "continue-" + UUID.randomUUID().toString().substring(0, 12);
    String requestKey = "request-" + UUID.randomUUID();
    String sourceA = "source-a-" + UUID.randomUUID();
    String sourceB = "source-b-" + UUID.randomUUID();
    String payload = JsonUtils.toJson(Map.of(
        "tenantId",
        tenant,
        "jobCode",
        "B",
        "bizDate",
        "2026-10-29",
        "batchDayCalendarCode",
        "CAL",
        "reason",
        "reason"));
    var frozenApproval = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("original-operator")
        .approvalReason("reason")
        .idempotencyKey(sourceB)
        .build();
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        "MANUAL_APPROVAL",
        "targets",
        List.of(
            Map.of("jobCode", "A", "sourceKey", sourceA, "workflowDefinitionId", 1),
            Map.of(
                "jobCode",
                "B",
                "sourceKey",
                sourceB,
                "workflowDefinitionId",
                2,
                "approval",
                frozenApproval))));
    String items = JsonUtils.toJson(List.of(
        Map.of("jobCode", "A", "sourceKey", sourceA, "status", "PENDING"),
        Map.of("jobCode", "B", "sourceKey", sourceB, "status", "PENDING")));
    String requestJson = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "jobCodes",
        List.of("A", "B"),
        "reason",
        "reason"));
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, requestKey, requestJson, snapshot, items));
    operationStore.startItem(tenant, prepared.requestKeyHash(), 0, sourceA);
    operationStore.acceptItem(tenant, prepared.requestKeyHash(), 1, sourceA, "apr-a");
    var ops = mock(ConsoleJobOperationsPort.class);
    when(ops.resolveTenant(tenant)).thenReturn(tenant);
    var approvalClient = mock(OrchestratorApprovalClient.class);
    when(approvalClient.submitApproval(any())).thenAnswer(invocation -> {
      var current = operationStore.findByRequestKey(tenant, requestKey);
      assertThat(current.itemsJson()).contains("IN_FLIGHT");
      assertThat((ApprovalSubmitCommand) invocation.getArgument(0)).isEqualTo(frozenApproval);
      return "apr-b";
    });
    var service = new DefaultConsoleJobApprovalService(
        ops,
        mock(TriggerInternalRestClient.class),
        mock(ConsoleRequestMetadataResolver.class),
        mock(BatchDayMapper.class),
        mock(BusinessCalendarMapper.class),
        mock(JobDefinitionMapper.class),
        mock(ManagedJobWorkflowReferencePort.class),
        operationStore,
        approvalClient);
    var result = service.continueBatchDay(tenant, requestKey);
    assertThat(result.items()).extracting("referenceNo").containsExactly("apr-a", "apr-b");
    assertThat(operationStore.findByRequestKey(tenant, requestKey).operationStatus())
        .isEqualTo("COMPLETE");
    service.continueBatchDay(tenant, requestKey);
    Mockito.verify(approvalClient, Mockito.times(1)).submitApproval(any());
  }

  @Test
  @DisplayName("自动补跑按冻结载荷回查原 Trigger，匹配才确认")
  void shouldReconcileAutoOnlyAfterVerifiedTriggerReceipt() {
    var fixture = continuationFixture("AUTO");
    var inFlight = operationStore.startItem(
        fixture.tenant(), fixture.operation().requestKeyHash(), 0, fixture.sourceKey());
    operationStore.markItemUnknown(
        fixture.tenant(), inFlight.requestKeyHash(), inFlight.revision(), fixture.sourceKey());
    var builder = RestClient.builder().baseUrl("http://trigger.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var trigger = mock(TriggerInternalRestClient.class);
    when(trigger.client()).thenReturn(builder.build());
    String expected = JsonUtils.toJson(Map.of(
        "tenantId",
        fixture.tenant(),
        "workflowDefinitionId",
        2,
        "bizDate",
        "2026-10-29",
        "triggerType",
        "CATCH_UP",
        "params",
        Map.of(
            "operationType",
            "BATCH_DAY_CATCH_UP",
            "approvalMode",
            "AUTO",
            "batchDayCatchUp",
            true,
            "batchDayBizDate",
            "2026-10-29",
            "batchDayCalendarCode",
            "CAL",
            "jobCode",
            "B",
            "reason",
            "reason",
            "catchUpPolicy",
            "AUTO")));
    server
        .expect(MockRestRequestMatchers.requestTo(
            "http://trigger.test/internal/trigger/catch-up/verify"))
        .andExpect(MockRestRequestMatchers.header("Idempotency-Key", fixture.sourceKey()))
        .andExpect(MockRestRequestMatchers.content().json(expected))
        .andRespond(MockRestResponseCreators.withSuccess(
            "{\"code\":\"SUCCESS\",\"data\":{\"instanceNo\":\"req-original\",\"traceId\":\"trace\"}}",
            MediaType.APPLICATION_JSON));
    var reconciliation =
        new BatchDayCatchUpReconciliation(operationStore, fixture.approvals(), trigger);
    var resolved = reconciliation.reconcileOriginal(fixture.tenant(), fixture.requestKey(), "B");
    assertThat(resolved.operationStatus()).isEqualTo("COMPLETE");
    assertThat(resolved.itemsJson()).contains("ACCEPTED", "req-original");
    verifyNoInteractions(fixture.approvals());
    server.verify();
  }

  @Test
  @DisplayName("Trigger 未找到、载荷冲突或网络故障时保持自动补跑未知")
  void shouldKeepAutoUnknownWhenVerificationFails() {
    var fixture = continuationFixture("AUTO");
    var inFlight = operationStore.startItem(
        fixture.tenant(), fixture.operation().requestKeyHash(), 0, fixture.sourceKey());
    operationStore.markItemUnknown(
        fixture.tenant(), inFlight.requestKeyHash(), inFlight.revision(), fixture.sourceKey());
    var builder = RestClient.builder().baseUrl("http://trigger.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var trigger = mock(TriggerInternalRestClient.class);
    when(trigger.client()).thenReturn(builder.build());
    String url = "http://trigger.test/internal/trigger/catch-up/verify";
    server
        .expect(MockRestRequestMatchers.requestTo(url))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
    server
        .expect(MockRestRequestMatchers.requestTo(url))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.CONFLICT));
    server.expect(MockRestRequestMatchers.requestTo(url)).andRespond(request -> {
      throw new IOException("response lost");
    });
    server
        .expect(MockRestRequestMatchers.requestTo(url))
        .andRespond(MockRestResponseCreators.withSuccess(
            "{\"code\":\"STATE_CONFLICT\",\"data\":{\"instanceNo\":\"unexpected\"}}",
            MediaType.APPLICATION_JSON));
    var reconciliation =
        new BatchDayCatchUpReconciliation(operationStore, fixture.approvals(), trigger);
    for (int attempt = 0; attempt < 4; attempt++) {
      assertThatThrownBy(
              () -> reconciliation.reconcileOriginal(fixture.tenant(), fixture.requestKey(), "B"))
          .isInstanceOf(RuntimeException.class);
      var saved = operationStore.findByRequestKey(fixture.tenant(), fixture.requestKey());
      assertThat(saved.operationStatus()).isEqualTo("UNKNOWN");
      assertThat(saved.revision()).isEqualTo(2);
    }
    verifyNoInteractions(fixture.approvals());
    Mockito.verify(fixture.ops(), Mockito.never()).delegateLaunch(any());
    server.verify();
  }

  @Test
  @DisplayName("原触发回执已持久化时可核对在途项目，并以版本围栏挡住旧调用")
  void shouldReconcileInFlightAfterVerifiedTriggerReceipt() {
    var fixture = continuationFixture("AUTO");
    var inFlight = operationStore.startItem(
        fixture.tenant(), fixture.operation().requestKeyHash(), 0, fixture.sourceKey());
    var builder = RestClient.builder().baseUrl("http://trigger.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var trigger = mock(TriggerInternalRestClient.class);
    when(trigger.client()).thenReturn(builder.build());
    server
        .expect(MockRestRequestMatchers.requestTo(
            "http://trigger.test/internal/trigger/catch-up/verify"))
        .andRespond(MockRestResponseCreators.withSuccess(
            "{\"code\":\"SUCCESS\",\"data\":{\"instanceNo\":\"req-original\"}}",
            MediaType.APPLICATION_JSON));
    var reconciliation =
        new BatchDayCatchUpReconciliation(operationStore, fixture.approvals(), trigger);
    var resolved = reconciliation.reconcileOriginal(fixture.tenant(), fixture.requestKey(), "B");
    assertThat(resolved.operationStatus()).isEqualTo("COMPLETE");
    assertThat(resolved.itemsJson()).contains("ACCEPTED", "req-original");
    assertThatThrownBy(() -> operationStore.acceptItem(
            fixture.tenant(),
            inFlight.requestKeyHash(),
            inFlight.revision(),
            fixture.sourceKey(),
            "req-original"))
        .isInstanceOf(BizException.class);
    server.verify();
  }

  @Test
  @DisplayName("在途项目查询不到回执时不能转为已确认或重发")
  void shouldPreserveInFlightWhenReceiptNotFound() {
    var fixture = continuationFixture("AUTO");
    var inFlight = operationStore.startItem(
        fixture.tenant(), fixture.operation().requestKeyHash(), 0, fixture.sourceKey());
    var builder = RestClient.builder().baseUrl("http://trigger.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var trigger = mock(TriggerInternalRestClient.class);
    when(trigger.client()).thenReturn(builder.build());
    server
        .expect(MockRestRequestMatchers.requestTo(
            "http://trigger.test/internal/trigger/catch-up/verify"))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
    var reconciliation =
        new BatchDayCatchUpReconciliation(operationStore, fixture.approvals(), trigger);
    assertThatThrownBy(
            () -> reconciliation.reconcileOriginal(fixture.tenant(), fixture.requestKey(), "B"))
        .isInstanceOf(HttpClientErrorException.class);
    var unchanged = operationStore.findByRequestKey(fixture.tenant(), fixture.requestKey());
    assertThat(unchanged.revision()).isEqualTo(inFlight.revision());
    assertThat(unchanged.itemsJson()).contains("IN_FLIGHT");
    Mockito.verify(fixture.ops(), Mockito.never()).delegateLaunch(any());
    server.verify();
  }

  @Test
  @DisplayName("原审批回执已持久化时可核对在途项目")
  void shouldReconcileInFlightAfterVerifiedApprovalReceipt() {
    var fixture = continuationFixture("MANUAL_APPROVAL");
    operationStore.startItem(
        fixture.tenant(), fixture.operation().requestKeyHash(), 0, fixture.sourceKey());
    when(fixture.approvals().lookupVerifiedSubmission(any()))
        .thenReturn(OrchestratorApprovalClient.ApprovalSourceRecord.builder()
            .tenantId(fixture.tenant())
            .sourceIdempotencyKey(fixture.sourceKey())
            .approvalNo("apr-original")
            .build());
    var reconciliation = new BatchDayCatchUpReconciliation(
        operationStore, fixture.approvals(), mock(TriggerInternalRestClient.class));
    var resolved = reconciliation.reconcileOriginal(fixture.tenant(), fixture.requestKey(), "B");
    assertThat(resolved.operationStatus()).isEqualTo("COMPLETE");
    assertThat(resolved.itemsJson()).contains("ACCEPTED", "apr-original");
    Mockito.verify(fixture.approvals(), Mockito.never()).submitApproval(any());
  }

  @Test
  @DisplayName("在途或未知项目不能通过接续重发")
  void shouldRejectContinuationOfUnresolvedItem() {
    var fixture = continuationFixture("MANUAL_APPROVAL");
    var running = operationStore.startItem(
        fixture.tenant(), fixture.operation().requestKeyHash(), 0, fixture.sourceKey());
    assertThatThrownBy(
            () -> fixture.service().continueBatchDay(fixture.tenant(), fixture.requestKey()))
        .isInstanceOf(BizException.class);
    operationStore.markItemUnknown(
        fixture.tenant(), running.requestKeyHash(), running.revision(), fixture.sourceKey());
    assertThatThrownBy(
            () -> fixture.service().continueBatchDay(fixture.tenant(), fixture.requestKey()))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(fixture.approvals());
    assertThat(operationStore
            .findByRequestKey(fixture.tenant(), fixture.requestKey())
            .operationStatus())
        .isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("并发接续只允许一个调用发出同一项目")
  void shouldFenceConcurrentContinuation() throws Exception {
    var fixture = continuationFixture("MANUAL_APPROVAL");
    var arrived = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(fixture.approvals().submitApproval(any())).thenAnswer(call -> {
      arrived.countDown();
      if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
      return "apr-b";
    });
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(
          () -> fixture.service().continueBatchDay(fixture.tenant(), fixture.requestKey()));
      assertThat(arrived.await(15, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(
              () -> fixture.service().continueBatchDay(fixture.tenant(), fixture.requestKey()))
          .isInstanceOf(BizException.class);
      release.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS).items()).hasSize(1);
      Mockito.verify(fixture.approvals(), Mockito.times(1)).submitApproval(any());
      assertThat(operationStore
              .findByRequestKey(fixture.tenant(), fixture.requestKey())
              .operationStatus())
          .isEqualTo("COMPLETE");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("接续调用响应丢失后保留未知并阻止再次发送")
  void shouldStopContinuationWhenResponseIsLost() {
    var fixture = continuationFixture("MANUAL_APPROVAL");
    when(fixture.approvals().submitApproval(any()))
        .thenThrow(new ResourceAccessException("response lost"));
    assertThatThrownBy(
            () -> fixture.service().continueBatchDay(fixture.tenant(), fixture.requestKey()))
        .isInstanceOf(ResourceAccessException.class);
    assertThat(operationStore
            .findByRequestKey(fixture.tenant(), fixture.requestKey())
            .operationStatus())
        .isEqualTo("UNKNOWN");
    assertThatThrownBy(
            () -> fixture.service().continueBatchDay(fixture.tenant(), fixture.requestKey()))
        .isInstanceOf(BizException.class);
    Mockito.verify(fixture.approvals(), Mockito.times(1)).submitApproval(any());
  }

  @Test
  @DisplayName("自动策略接续使用原冻结目标和请求键")
  void shouldContinueFrozenAutoTarget() {
    var fixture = continuationFixture("AUTO");
    when(fixture.ops().delegateLaunch(any())).thenAnswer(call -> {
      var command = (ConsoleLaunchCommand) call.getArgument(0);
      assertThat(command.tenantId()).isEqualTo(fixture.tenant());
      assertThat(command.workflowDefinitionId()).isEqualTo(2L);
      assertThat(command.jobCode()).isEqualTo("B");
      assertThat(command.bizDate()).isEqualTo("2026-10-29");
      assertThat(command.idempotencyKey()).isEqualTo(fixture.sourceKey());
      assertThat(command.params()).containsEntry("reason", "reason");
      assertThat(operationStore
              .findByRequestKey(fixture.tenant(), fixture.requestKey())
              .itemsJson())
          .contains("IN_FLIGHT");
      return "instance-b";
    });
    assertThat(fixture
            .service()
            .continueBatchDay(fixture.tenant(), fixture.requestKey())
            .items())
        .extracting("referenceNo")
        .containsExactly("instance-b");
    verifyNoInteractions(fixture.approvals());
    Mockito.verify(fixture.ops(), Mockito.times(1)).delegateLaunch(any());
  }

  private ContinuationFixture continuationFixture(String policy) {
    String tenant = "continue-fixture-" + UUID.randomUUID().toString().substring(0, 12);
    String requestKey = "request-" + UUID.randomUUID();
    String sourceKey = "source-" + UUID.randomUUID();
    String payload = JsonUtils.toJson(Map.of(
        "tenantId",
        tenant,
        "jobCode",
        "B",
        "bizDate",
        "2026-10-29",
        "batchDayCalendarCode",
        "CAL",
        "reason",
        "reason"));
    var approval = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("original-operator")
        .approvalReason("reason")
        .idempotencyKey(sourceKey)
        .build();
    var target = new LinkedHashMap<String, Object>();
    target.put("jobCode", "B");
    target.put("sourceKey", sourceKey);
    target.put("workflowDefinitionId", 2L);
    if ("MANUAL_APPROVAL".equals(policy)) target.put("approval", approval);
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        policy,
        "targets",
        List.of(target)));
    String items = JsonUtils.toJson(
        List.of(Map.of("jobCode", "B", "sourceKey", sourceKey, "status", "PENDING")));
    String identity = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "jobCodes",
        List.of("B"),
        "reason",
        "reason"));
    var operation = operationStore.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, identity, snapshot, items));
    var ops = mock(ConsoleJobOperationsPort.class);
    when(ops.resolveTenant(tenant)).thenReturn(tenant);
    var approvals = mock(OrchestratorApprovalClient.class);
    var service = new DefaultConsoleJobApprovalService(
        ops,
        mock(TriggerInternalRestClient.class),
        mock(ConsoleRequestMetadataResolver.class),
        mock(BatchDayMapper.class),
        mock(BusinessCalendarMapper.class),
        mock(JobDefinitionMapper.class),
        mock(ManagedJobWorkflowReferencePort.class),
        operationStore,
        approvals);
    return new ContinuationFixture(
        tenant, requestKey, sourceKey, operation, ops, approvals, service);
  }

  private record ContinuationFixture(
      String tenant,
      String requestKey,
      String sourceKey,
      BatchDayCatchUpOperation operation,
      ConsoleJobOperationsPort ops,
      OrchestratorApprovalClient approvals,
      DefaultConsoleJobApprovalService service) {}

  @Test
  @DisplayName("批量审批提交前冻结原请求，第二项连接失败时保留已确认和未知结果")
  void shouldFreezeBatchBeforeApprovalAndPreserveUnknownOutcome() {
    String tenant = "batch-entry-" + UUID.randomUUID().toString().substring(0, 12);
    var ops = mock(ConsoleJobOperationsPort.class);
    var calendars = mock(BusinessCalendarMapper.class);
    var jobs = mock(JobDefinitionMapper.class);
    var refs = mock(ManagedJobWorkflowReferencePort.class);
    var metadata = mock(ConsoleRequestMetadataResolver.class);
    when(metadata.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", tenant, "op", null, null));
    when(ops.resolveTenant(tenant)).thenReturn(tenant);
    when(ops.hasText(any()))
        .thenAnswer(call -> call.getArgument(0) instanceof String value && !value.isBlank());
    when(ops.parseBizDate("2026-10-29")).thenReturn(LocalDate.of(2026, 10, 29));
    when(calendars.selectActiveByTenantAndCalendarCode(tenant, "CAL"))
        .thenReturn(new ActiveCalendarRow(
            1L, tenant, "CAL", null, null, "MANUAL_APPROVAL", null, null, null));
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setId(1L);
    job.setEnabled(true);
    job.setDefaultWorkflowDefinitionId(10L);
    when(jobs.selectByUniqueKey(any(), any())).thenReturn(job);
    when(refs.isEnabledSingleJobWorkflow(tenant, 10L, 1L)).thenReturn(true);
    String query =
        "select items_json::text from batch.batch_day_catchup_operation where tenant_id = ?";
    AtomicInteger calls = new AtomicInteger();
    when(ops.submitApproval(any())).thenAnswer(call -> {
      String current = jdbc.queryForObject(query, String.class, tenant);
      assertThat(current).contains("IN_FLIGHT");
      if (calls.incrementAndGet() == 1) {
        return "apr-1";
      }
      throw new ResourceAccessException("response lost");
    });
    var service = new DefaultConsoleJobApprovalService(
        ops,
        mock(TriggerInternalRestClient.class),
        metadata,
        mock(BatchDayMapper.class),
        calendars,
        jobs,
        refs,
        operationStore,
        mock(OrchestratorApprovalClient.class));
    var request = new BatchDayCatchUpRequest();
    request.setTenantId(tenant);
    request.setCalendarCode("CAL");
    request.setJobCodes(List.of("JOB_A", "JOB_B"));
    String requestKey = "request-" + UUID.randomUUID();
    assertThatThrownBy(() -> service.catchUpBatchDay("2026-10-29", request, requestKey))
        .isInstanceOf(ResourceAccessException.class);
    String state = jdbc.queryForObject(query, String.class, tenant);
    assertThat(state).contains("ACCEPTED", "apr-1", "UNKNOWN");
    assertThat(calls).hasValue(2);
    assertThatThrownBy(() -> service.catchUpBatchDay("2026-10-29", request, requestKey))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("完成的默认候选批次重试返回原结果，不重新查询候选或创建审批")
  void shouldReplayFrozenDefaultCandidatesWithoutResubmission() {
    String tenant = "batch-replay-" + UUID.randomUUID().toString().substring(0, 12);
    var ops = mock(ConsoleJobOperationsPort.class);
    var calendars = mock(BusinessCalendarMapper.class);
    var jobs = mock(JobDefinitionMapper.class);
    var batchDays = mock(BatchDayMapper.class);
    var refs = mock(ManagedJobWorkflowReferencePort.class);
    var metadata = mock(ConsoleRequestMetadataResolver.class);
    when(metadata.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", tenant, "op", null, null));
    when(ops.resolveTenant(tenant)).thenReturn(tenant);
    LocalDate date = LocalDate.of(2026, 10, 29);
    when(ops.parseBizDate(date.toString())).thenReturn(date);
    when(calendars.selectActiveByTenantAndCalendarCode(tenant, "CAL"))
        .thenReturn(new ActiveCalendarRow(
            1L, tenant, "CAL", null, null, "MANUAL_APPROVAL", null, null, null));
    when(batchDays.selectFailedJobCodes(tenant, "CAL", date))
        .thenReturn(List.of("JOB_A"))
        .thenReturn(List.of("JOB_B"));
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setId(1L);
    job.setEnabled(true);
    job.setDefaultWorkflowDefinitionId(10L);
    when(jobs.selectByUniqueKey(tenant, "JOB_A")).thenReturn(job);
    when(refs.isEnabledSingleJobWorkflow(tenant, 10L, 1L)).thenReturn(true);
    when(ops.submitApproval(any())).thenReturn("apr-1");
    var service = new DefaultConsoleJobApprovalService(
        ops,
        mock(TriggerInternalRestClient.class),
        metadata,
        batchDays,
        calendars,
        jobs,
        refs,
        operationStore,
        mock(OrchestratorApprovalClient.class));
    var request = new BatchDayCatchUpRequest();
    request.setTenantId(tenant);
    request.setCalendarCode("CAL");
    String key = "request-" + UUID.randomUUID();
    var first = service.catchUpBatchDay(date.toString(), request, key);
    job.setEnabled(false);
    var replay = service.catchUpBatchDay(date.toString(), request, key);
    assertThat(replay).isEqualTo(first);
    assertThat(replay.items()).singleElement().satisfies(item -> {
      assertThat(item.jobCode()).isEqualTo("JOB_A");
      assertThat(item.referenceNo()).isEqualTo("apr-1");
    });
    Mockito.verify(batchDays).selectFailedJobCodes(tenant, "CAL", date);
    Mockito.verify(ops).submitApproval(any());
  }

  @Test
  @DisplayName("对账禁止在调用方事务中执行，避免远程调用期间占用数据库事务")
  void shouldRejectReconciliationInsideCallerTransaction() {
    var outer = new TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () -> outer.execute(status -> reconciliationProxy.reconcile("t1", "key", 0, "source")))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("根据原请求和作业定位未知审批，核验后只确认原回执")
  void shouldReconcileUsingOriginalRequestAndJob() {
    String tenant = "reconcile-original-" + UUID.randomUUID().toString().substring(0, 12);
    String requestKey = "request-" + UUID.randomUUID();
    String sourceKey = "source-" + UUID.randomUUID();
    String payload = "{\"tenantId\":\"" + tenant
        + "\",\"jobCode\":\"A\",\"bizDate\":\"2026-10-29\",\"batchDayCalendarCode\":\"CAL\"}";
    var expected = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("operator")
        .idempotencyKey(sourceKey)
        .build();
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        "MANUAL_APPROVAL",
        "targets",
        List.of(Map.of("jobCode", "A", "sourceKey", sourceKey, "approval", expected))));
    String items = JsonUtils.toJson(
        List.of(Map.of("jobCode", "A", "sourceKey", sourceKey, "status", "PENDING")));
    var prepared = operationStore.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, "{}", snapshot, items));
    operationStore.startItem(tenant, prepared.requestKeyHash(), 0, sourceKey);
    operationStore.markItemUnknown(tenant, prepared.requestKeyHash(), 1, sourceKey);
    OrchestratorApprovalClient approvals = mock(OrchestratorApprovalClient.class);
    when(approvals.lookupVerifiedSubmission(any()))
        .thenReturn(OrchestratorApprovalClient.ApprovalSourceRecord.builder()
            .tenantId(tenant)
            .sourceIdempotencyKey(sourceKey)
            .approvalNo("apr-1")
            .build());
    var reconciliation = new BatchDayCatchUpReconciliation(
        operationStore, approvals, mock(TriggerInternalRestClient.class));
    assertThatThrownBy(() -> reconciliation.reconcileOriginal(tenant, requestKey, "OTHER"))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(approvals);
    var resolved = reconciliation.reconcileOriginal(tenant, requestKey, "A");
    assertThat(resolved.itemsJson()).contains("ACCEPTED", "apr-1");
    assertThat(resolved.operationStatus()).isEqualTo("COMPLETE");
    assertThatThrownBy(() -> reconciliation.reconcileOriginal(tenant, requestKey, "A"))
        .isInstanceOf(BizException.class);
    Mockito.verify(approvals, Mockito.times(1)).lookupVerifiedSubmission(any());
  }

  @Test
  @DisplayName("原审批未查到时保留未知，已确认后续作业仍待接续")
  void shouldPreserveUnknownOrPendingWhenReconcilingOriginal() {
    String tenant = "reconcile-pending-" + UUID.randomUUID().toString().substring(0, 12);
    String requestKey = "request-" + UUID.randomUUID();
    String sourceKey = "source-" + UUID.randomUUID();
    String payload = "{\"tenantId\":\"" + tenant
        + "\",\"jobCode\":\"A\",\"bizDate\":\"2026-10-29\",\"batchDayCalendarCode\":\"CAL\"}";
    var expected = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("operator")
        .idempotencyKey(sourceKey)
        .build();
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        "MANUAL_APPROVAL",
        "targets",
        List.of(
            Map.of("jobCode", "A", "sourceKey", sourceKey, "approval", expected),
            Map.of("jobCode", "B", "sourceKey", "source-b"))));
    String items = JsonUtils.toJson(List.of(
        Map.of("jobCode", "A", "sourceKey", sourceKey, "status", "PENDING"),
        Map.of("jobCode", "B", "sourceKey", "source-b", "status", "PENDING")));
    var prepared = operationStore.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, "{}", snapshot, items));
    operationStore.startItem(tenant, prepared.requestKeyHash(), 0, sourceKey);
    operationStore.markItemUnknown(tenant, prepared.requestKeyHash(), 1, sourceKey);
    OrchestratorApprovalClient approvals = mock(OrchestratorApprovalClient.class);
    when(approvals.lookupVerifiedSubmission(any()))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND))
        .thenReturn(OrchestratorApprovalClient.ApprovalSourceRecord.builder()
            .tenantId(tenant)
            .sourceIdempotencyKey(sourceKey)
            .approvalNo("apr-1")
            .build());
    var reconciliation = new BatchDayCatchUpReconciliation(
        operationStore, approvals, mock(TriggerInternalRestClient.class));
    assertThatThrownBy(() -> reconciliation.reconcileOriginal(tenant, requestKey, "A"))
        .isInstanceOf(HttpClientErrorException.class);
    assertThat(operationStore.findByRequestKey(tenant, requestKey).operationStatus())
        .isEqualTo("UNKNOWN");
    var resolved = reconciliation.reconcileOriginal(tenant, requestKey, "A");
    assertThat(resolved.operationStatus()).isEqualTo("RUNNING");
    assertThat(resolved.itemsJson()).contains("ACCEPTED", "apr-1", "PENDING");
  }

  @Test
  @DisplayName("冻结审批载荷与作业身份不一致时不访问远端")
  void shouldRejectReconciliationWhenFrozenJobDiffers() {
    String tenant = "reconcile-invalid-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "source-" + UUID.randomUUID();
    String payload = "{\"tenantId\":\"" + tenant
        + "\",\"jobCode\":\"OTHER\",\"bizDate\":\"2026-10-29\",\"batchDayCalendarCode\":\"CAL\"}";
    var expected = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("operator")
        .idempotencyKey(key)
        .build();
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "targets",
        List.of(Map.of("jobCode", "A", "sourceKey", key, "approval", expected))));
    String items =
        JsonUtils.toJson(List.of(Map.of("jobCode", "A", "sourceKey", key, "status", "PENDING")));
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "request-" + UUID.randomUUID(), "{}", snapshot, items));
    operationStore.startItem(tenant, prepared.requestKeyHash(), 0, key);
    operationStore.markItemUnknown(tenant, prepared.requestKeyHash(), 1, key);
    OrchestratorApprovalClient approvals = mock(OrchestratorApprovalClient.class);
    var reconciliation = new BatchDayCatchUpReconciliation(
        operationStore, approvals, mock(TriggerInternalRestClient.class));
    assertThatThrownBy(() -> reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(approvals);
    assertThat(operations.select(tenant, prepared.requestKeyHash()).operationStatus())
        .isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("并发回查同一未知项目时只有一个确认成功")
  void shouldFenceConcurrentApprovalReconciliation() throws Exception {
    String tenant = "reconcile-race-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "source-" + UUID.randomUUID();
    String payload = "{\"tenantId\":\"" + tenant
        + "\",\"jobCode\":\"A\",\"bizDate\":\"2026-10-29\",\"batchDayCalendarCode\":\"CAL\"}";
    var expected = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("operator")
        .idempotencyKey(key)
        .build();
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "targets",
        List.of(Map.of("jobCode", "A", "sourceKey", key, "approval", expected))));
    String items =
        JsonUtils.toJson(List.of(Map.of("jobCode", "A", "sourceKey", key, "status", "PENDING")));
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "request-" + UUID.randomUUID(), "{}", snapshot, items));
    operationStore.startItem(tenant, prepared.requestKeyHash(), 0, key);
    operationStore.markItemUnknown(tenant, prepared.requestKeyHash(), 1, key);
    var bothLookups = new CountDownLatch(2);
    OrchestratorApprovalClient approvals = mock(OrchestratorApprovalClient.class);
    when(approvals.lookupVerifiedSubmission(any())).thenAnswer(invocation -> {
      bothLookups.countDown();
      if (!bothLookups.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("both lookup calls did not arrive");
      }
      return OrchestratorApprovalClient.ApprovalSourceRecord.builder()
          .tenantId(tenant)
          .sourceIdempotencyKey(key)
          .approvalNo("apr-1")
          .build();
    });
    var reconciliation = new BatchDayCatchUpReconciliation(
        operationStore, approvals, mock(TriggerInternalRestClient.class));
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Object> attempt = () -> {
        try {
          return reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key);
        } catch (BizException conflict) {
          return conflict;
        }
      };
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
      assertThat(results.stream().filter(value -> value instanceof BizException).count())
          .isEqualTo(1);
      assertThat(results.stream()
              .filter(value -> value instanceof BatchDayCatchUpOperation)
              .count())
          .isEqualTo(1);
      assertThat(operations.select(tenant, prepared.requestKeyHash()).revision())
          .isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("审批回查失败保持未知；核验原载荷后只确认对应项目")
  void shouldReconcileUnknownApprovalOnlyAfterVerifiedLookup() {
    String tenant = "reconcile-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "source-" + UUID.randomUUID();
    String payload = "{\"tenantId\":\"" + tenant
        + "\",\"jobCode\":\"A\",\"bizDate\":\"2026-10-29\",\"batchDayCalendarCode\":\"CAL\"}";
    ApprovalSubmitCommand expected = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("operator")
        .idempotencyKey(key)
        .build();
    String snapshot = JsonUtils.toJson(Map.of(
        "bizDate",
        "2026-10-29",
        "calendarCode",
        "CAL",
        "targets",
        List.of(Map.of("jobCode", "A", "sourceKey", key, "approval", expected))));
    String items =
        JsonUtils.toJson(List.of(Map.of("jobCode", "A", "sourceKey", key, "status", "PENDING")));
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "request-" + UUID.randomUUID(), "{}", snapshot, items));
    operationStore.startItem(tenant, prepared.requestKeyHash(), 0, key);
    operationStore.markItemUnknown(tenant, prepared.requestKeyHash(), 1, key);

    OrchestratorApprovalClient approvals = mock(OrchestratorApprovalClient.class);
    var reconciliation = new BatchDayCatchUpReconciliation(
        operationStore, approvals, mock(TriggerInternalRestClient.class));
    when(approvals.lookupVerifiedSubmission(any()))
        .thenThrow(new ResourceAccessException("unavailable"))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND))
        .thenThrow(
            BizException.of(ResultCode.STATE_CONFLICT, "error.approval.idempotency_conflict"))
        .thenReturn(OrchestratorApprovalClient.ApprovalSourceRecord.builder()
            .tenantId(tenant)
            .sourceIdempotencyKey(key)
            .approvalNo("apr-1")
            .build());
    assertThatThrownBy(() -> reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key))
        .isInstanceOf(ResourceAccessException.class);
    assertThat(operations.select(tenant, prepared.requestKeyHash()).operationStatus())
        .isEqualTo("UNKNOWN");
    assertThatThrownBy(() -> reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key))
        .isInstanceOf(HttpClientErrorException.class);
    assertThatThrownBy(() -> reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key))
        .isInstanceOf(BizException.class);
    assertThat(operations.select(tenant, prepared.requestKeyHash()).operationStatus())
        .isEqualTo("UNKNOWN");
    var resolved = reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key);
    assertThat(resolved.operationStatus()).isEqualTo("RUNNING");
    assertThat(resolved.itemsJson()).contains("ACCEPTED", "apr-1");
    assertThatThrownBy(() -> reconciliation.reconcile(tenant, prepared.requestKeyHash(), 2, key))
        .isInstanceOf(BizException.class);
    assertThat(operationStore.complete(tenant, prepared.requestKeyHash(), 3).operationStatus())
        .isEqualTo("COMPLETE");
  }

  @Test
  @DisplayName("普通数据库角色下放弃围栏按租户隔离且同键互不影响")
  void shouldIsolateAbandonFenceForOrdinaryDatabaseRole() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String role = "catchup_fence_" + suffix;
    String tenantA = "fence-a-" + suffix;
    String tenantB = "fence-b-" + suffix;
    String requestKey = "shared-" + suffix;
    String visibleSql = "select tenant_id from batch.batch_day_catchup_operation";
    String cleanupSql = "delete from batch.batch_day_catchup_operation where tenant_id in (?, ?)";
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      try (var statement = connection.createStatement()) {
        String createRoleSql = "create role " + role + " nologin";
        statement.execute(createRoleSql);
        try {
          String grantSchemaSql = "grant usage on schema batch to " + role;
          String grantTableSql =
              "grant select, insert on batch.batch_day_catchup_operation to " + role;
          String setRoleSql = "set role " + role;
          statement.execute(grantSchemaSql);
          statement.execute(grantTableSql);
          statement.execute(setRoleSql);
          var dataSource = new SingleConnectionDataSource(connection, true);
          var factoryBean = new SqlSessionFactoryBean();
          factoryBean.setDataSource(dataSource);
          factoryBean.setMapperLocations(
              new ClassPathResource("mapper/BatchDayCatchUpOperationMapper.xml"));
          var mapper = new SqlSessionTemplate(factoryBean.getObject())
              .getMapper(BatchDayCatchUpOperationMapper.class);
          var store = new BatchDayCatchUpOperationStore(mapper);
          var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
          var scopedJdbc = new JdbcTemplate(dataSource);

          var first = transaction.execute(status -> {
            var result = store.abandonUnprepared(tenantA, requestKey, "2026-10-14", "CAL_A");
            assertThat(scopedJdbc.queryForList(visibleSql, String.class)).containsExactly(tenantA);
            return result;
          });
          assertThat(first.operationStatus()).isEqualTo("ABANDONED");
          var notFound = transaction.execute(status -> store.findByRequestKey(tenantB, requestKey));
          assertThat(notFound).isNull();
          var second = transaction.execute(status -> {
            var result = store.abandonUnprepared(tenantB, requestKey, "2026-10-15", "CAL_B");
            assertThat(scopedJdbc.queryForList(visibleSql, String.class)).containsExactly(tenantB);
            return result;
          });
          assertThat(second.operationStatus()).isEqualTo("ABANDONED");
          var again = transaction.execute(status -> store.findByRequestKey(tenantA, requestKey));
          assertThat(again.snapshotJson()).contains("CAL_A").doesNotContain("CAL_B");
          assertThatThrownBy(() -> transaction.execute(
                  status -> store.prepare(new BatchDayCatchUpOperationStore.Prepare(
                      tenantA, requestKey, "{}", "{}", "[]"))))
              .isInstanceOfSatisfying(
                  BizException.class,
                  failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
        } finally {
          String resetRoleSql = "reset role";
          String dropOwnedSql = "drop owned by " + role;
          String dropRoleSql = "drop role " + role;
          statement.execute(resetRoleSql);
          statement.execute(dropOwnedSql);
          statement.execute(dropRoleSql);
          try (var cleanup = connection.prepareStatement(cleanupSql)) {
            cleanup.setString(1, tenantA);
            cleanup.setString(2, tenantB);
            cleanup.executeUpdate();
          }
        }
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
      return null;
    });
  }

  @Test
  @DisplayName("普通数据库角色执行补跑记录服务时自动设置租户并隔离其他租户")
  void shouldUseTenantContextForStoreWithOrdinaryRole() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String role = "catchup_store_" + suffix;
    String tenantA = "store-a-" + suffix;
    String tenantB = "store-b-" + suffix;
    String requestKey = "request-" + suffix;
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      try (var statement = connection.createStatement()) {
        String createRoleSql = "create role " + role + " nologin";
        statement.execute(createRoleSql);
        try {
          String grantSchemaSql = "grant usage on schema batch to " + role;
          String grantTableSql =
              "grant select, insert, update on batch.batch_day_catchup_operation to " + role;
          String setRoleSql = "set role " + role;
          statement.execute(grantSchemaSql);
          statement.execute(grantTableSql);
          statement.execute(setRoleSql);
          var dataSource = new SingleConnectionDataSource(connection, true);
          var factoryBean = new SqlSessionFactoryBean();
          factoryBean.setDataSource(dataSource);
          factoryBean.setMapperLocations(
              new ClassPathResource("mapper/BatchDayCatchUpOperationMapper.xml"));
          var factory = factoryBean.getObject();
          var mapper =
              new SqlSessionTemplate(factory).getMapper(BatchDayCatchUpOperationMapper.class);
          var store = new BatchDayCatchUpOperationStore(mapper);
          var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
          String snapshot = "{\"targets\":[{\"jobCode\":\"A\",\"sourceKey\":\"key-a\"}]}";
          String items = """
              [{"jobCode":"A","sourceKey":"key-a","status":"PENDING","referenceNo":null}]
              """;
          var prepared =
              transaction.execute(status -> store.prepare(new BatchDayCatchUpOperationStore.Prepare(
                  tenantA, requestKey, "{}", snapshot, items)));
          assertThat(prepared).isNotNull();
          var foundA = transaction.execute(status -> store.find(tenantA, requestKey, "{}"));
          var foundB = transaction.execute(status -> store.find(tenantB, requestKey, "{}"));
          assertThat(foundA).isNotNull();
          assertThat(foundB).isNull();
          transaction.execute(
              status -> store.startItem(tenantA, prepared.requestKeyHash(), 0, "key-a"));
          transaction.execute(
              status -> store.acceptItem(tenantA, prepared.requestKeyHash(), 1, "key-a", "apr-a"));
          var completed =
              transaction.execute(status -> store.complete(tenantA, prepared.requestKeyHash(), 2));
          assertThat(completed.operationStatus()).isEqualTo("COMPLETE");
        } finally {
          String resetRoleSql = "reset role";
          String dropOwnedSql = "drop owned by " + role;
          String dropRoleSql = "drop role " + role;
          statement.execute(resetRoleSql);
          statement.execute(dropOwnedSql);
          statement.execute(dropRoleSql);
        }
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
      return null;
    });
  }

  @Test
  @DisplayName("外层事务回滚后远端发送前的认领记录仍已提交")
  void shouldCommitInFlightIntentBeforeCallerTransactionRollsBack() {
    String tenant = "catchup-commit-" + UUID.randomUUID().toString().substring(0, 12);
    String snapshot = "{\"targets\":[{\"jobCode\":\"A\",\"sourceKey\":\"key-a\"}]}";
    String items = """
        [{"jobCode":"A","sourceKey":"key-a","status":"PENDING","referenceNo":null}]
        """;
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "request-" + UUID.randomUUID(), "{}", snapshot, items));
    var outer = new TransactionTemplate(transactionManager);
    outer.executeWithoutResult(status -> {
      operationStore.startItem(tenant, prepared.requestKeyHash(), 0, "key-a");
      status.setRollbackOnly();
    });

    var afterRollback = operations.select(tenant, prepared.requestKeyHash());
    assertThat(afterRollback.operationStatus()).isEqualTo("RUNNING");
    assertThat(afterRollback.itemsJson()).contains("IN_FLIGHT");
    assertThat(afterRollback.revision()).isEqualTo(1);
  }

  @Test
  @DisplayName("冻结请求和确认回执分别独立提交，不随调用方事务回滚")
  void shouldCommitFrozenRequestAndReceiptIndependently() {
    String tenant = "catchup-boundary-" + UUID.randomUUID().toString().substring(0, 12);
    String requestKey = "request-" + UUID.randomUUID();
    String snapshot = "{\"targets\":[{\"jobCode\":\"A\",\"sourceKey\":\"key-a\"}]}";
    String items = """
        [{"jobCode":"A","sourceKey":"key-a","status":"PENDING","referenceNo":null}]
        """;
    var outer = new TransactionTemplate(transactionManager);
    var frozen = outer.execute(status -> {
      var result = operationStore.prepare(
          new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, "{}", snapshot, items));
      status.setRollbackOnly();
      return result;
    });
    assertThat(frozen).isNotNull();
    assertThat(operationStore.find(tenant, requestKey, "{}")).isNotNull();
    operationStore.startItem(tenant, frozen.requestKeyHash(), 0, "key-a");
    outer.executeWithoutResult(status -> {
      operationStore.acceptItem(tenant, frozen.requestKeyHash(), 1, "key-a", "approval-a");
      status.setRollbackOnly();
    });

    var accepted = operations.select(tenant, frozen.requestKeyHash());
    assertThat(accepted.itemsJson()).contains("ACCEPTED", "approval-a");
    assertThat(accepted.revision()).isEqualTo(2);
    assertThat(operationStore.complete(tenant, frozen.requestKeyHash(), 2).operationStatus())
        .isEqualTo("COMPLETE");
  }

  @Test
  @DisplayName("先持久化提交中再确认回执，结果未知后停止后续项目")
  void shouldPersistItemTransitionsAndStopOnUnknownOutcome() {
    String tenant = "catchup-items-" + UUID.randomUUID().toString().substring(0, 12);
    String snapshot = """
        {"targets":[{"jobCode":"A","sourceKey":"key-a"},{"jobCode":"B","sourceKey":"key-b"}]}
        """;
    String items = """
        [{"jobCode":"A","sourceKey":"key-a","status":"PENDING","referenceNo":null},
         {"jobCode":"B","sourceKey":"key-b","status":"PENDING","referenceNo":null}]
        """;
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "request-" + UUID.randomUUID(), "{}", snapshot, items));
    String keyHash = prepared.requestKeyHash();

    var firstInFlight = operationStore.startItem(tenant, keyHash, 0, "key-a");
    assertThat(firstInFlight.operationStatus()).isEqualTo("RUNNING");
    assertThat(firstInFlight.itemsJson()).contains("IN_FLIGHT");
    assertThatThrownBy(() -> operationStore.startItem(tenant, keyHash, 1, "key-b"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    var firstAccepted = operationStore.acceptItem(tenant, keyHash, 1, "key-a", "approval-a");
    assertThat(firstAccepted.itemsJson()).contains("ACCEPTED", "approval-a");
    var secondInFlight = operationStore.startItem(tenant, keyHash, 2, "key-b");
    assertThat(secondInFlight.revision()).isEqualTo(3);
    var unknown = operationStore.markItemUnknown(tenant, keyHash, 3, "key-b");
    assertThat(unknown.operationStatus()).isEqualTo("UNKNOWN");
    assertThat(unknown.itemsJson()).contains("UNKNOWN");
    assertThatThrownBy(() -> operationStore.startItem(tenant, keyHash, 4, "key-b"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> operationStore.acceptItem(tenant, keyHash, 4, "key-b", "approval-b"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> operationStore.complete(tenant, keyHash, 4))
        .isInstanceOf(BizException.class);
    assertThat(operations.select(tenant, keyHash).operationStatus()).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("冻结身份不匹配或旧版本回执不能改变补跑状态")
  void shouldRejectMismatchedItemAndStaleReceipt() {
    String tenant = "catchup-item-guard-" + UUID.randomUUID().toString().substring(0, 12);
    String snapshot = "{\"targets\":[{\"jobCode\":\"A\",\"sourceKey\":\"key-a\"}]}";
    String wrongItems = """
        [{"jobCode":"A","sourceKey":"key-other","status":"PENDING","referenceNo":null}]
        """;
    var wrong = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "wrong-" + UUID.randomUUID(), "{}", snapshot, wrongItems));
    assertThatThrownBy(
            () -> operationStore.startItem(tenant, wrong.requestKeyHash(), 0, "key-other"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    assertThat(operations.select(tenant, wrong.requestKeyHash()).operationStatus())
        .isEqualTo("PREPARED");

    String correctItems = """
        [{"jobCode":"A","sourceKey":"key-a","status":"PENDING","referenceNo":null}]
        """;
    var correct = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, "correct-" + UUID.randomUUID(), "{}", snapshot, correctItems));
    var inFlight = operationStore.startItem(tenant, correct.requestKeyHash(), 0, "key-a");
    assertThat(inFlight.revision()).isEqualTo(1);
    assertThatThrownBy(
            () -> operationStore.acceptItem(tenant, correct.requestKeyHash(), 0, "key-a", "apr-1"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    assertThat(operations.select(tenant, correct.requestKeyHash()).itemsJson())
        .contains("IN_FLIGHT");
  }

  @Test
  @DisplayName("未确认的逐项结果不能使批次完成")
  void shouldRequireAllPersistedItemsAcceptedBeforeCompleting() {
    String tenant = "catchup-complete-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    String request = "{\"bizDate\":\"2026-10-14\"}";
    String unknownItem = """
        [{"jobCode":"A","sourceKey":"key-a","status":"UNKNOWN","referenceNo":null}]
        """;
    String acceptedItem = """
        [{"jobCode":"A","sourceKey":"key-a","status":"ACCEPTED","referenceNo":"approval-a"}]
        """;
    var prepared = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, key, request, "{\"targets\":[{\"jobCode\":\"A\",\"sourceKey\":\"key-a\"}]}", "[]"));
    String keyHash = prepared.requestKeyHash();
    assertThatThrownBy(() -> operationStore.complete(tenant, keyHash, 0))
        .isInstanceOf(BizException.class);
    assertThat(operations.transition(new BatchDayCatchUpOperationTransition(
            tenant, keyHash, 0, "PREPARED", "RUNNING", "[]")))
        .isEqualTo(1);
    assertThat(operations.transition(new BatchDayCatchUpOperationTransition(
            tenant, keyHash, 1, "RUNNING", "UNKNOWN", unknownItem)))
        .isEqualTo(1);

    assertThatThrownBy(() -> operationStore.complete(tenant, keyHash, 2))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    assertThat(operations.select(tenant, keyHash).operationStatus()).isEqualTo("UNKNOWN");

    assertThat(operations.transition(new BatchDayCatchUpOperationTransition(
            tenant, keyHash, 2, "UNKNOWN", "UNKNOWN", acceptedItem)))
        .isEqualTo(1);
    var completed = operationStore.complete(tenant, keyHash, 3);
    assertThat(completed.operationStatus()).isEqualTo("COMPLETE");
    assertThat(completed.itemsJson()).contains("approval-a");
  }

  @Test
  @DisplayName("已确认项目的来源身份与冻结目标不一致时不能完成")
  void shouldRejectCompletionWhenConfirmedIdentityDiffersFromSnapshot() {
    String tenant = "catchup-mismatch-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    String request = "{\"bizDate\":\"2026-10-14\"}";
    String snapshot = "{\"targets\":[{\"jobCode\":\"A\",\"sourceKey\":\"key-a\"}]}";
    String wrongItem = """
        [{"jobCode":"A","sourceKey":"key-other","status":"ACCEPTED","referenceNo":"approval-a"}]
        """;
    var prepared = operationStore.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, key, request, snapshot, wrongItem));

    assertThatThrownBy(() -> operationStore.complete(tenant, prepared.requestKeyHash(), 0))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    assertThat(operations.select(tenant, prepared.requestKeyHash()).operationStatus())
        .isEqualTo("PREPARED");
  }

  @Test
  @DisplayName("未建档原键设置放弃围栏后不能再冻结或发送")
  void shouldFenceUnpreparedRequestBeforeAdmission() {
    String tenant = "fence-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    var abandoned = operationStore.abandonUnprepared(tenant, key, "2026-10-14", "CAL");
    assertThat(abandoned.operationStatus()).isEqualTo("ABANDONED");
    assertThat(abandoned.snapshotJson()).contains("2026-10-14", "CAL");
    assertThatThrownBy(() -> operationStore.prepare(
            new BatchDayCatchUpOperationStore.Prepare(tenant, key, "{}", "{\"targets\":[]}", "[]")))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    assertThat(operationStore.findByRequestKey(tenant, key).operationStatus())
        .isEqualTo("ABANDONED");
  }

  @Test
  @DisplayName("已冻结请求不能被未建档放弃操作覆盖")
  void shouldPreservePreparedRequestWhenAbandonmentArrivesLate() {
    String tenant = "fence-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    var prepared = operationStore.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, key, "{}", "{\"targets\":[]}", "[]"));
    var result = operationStore.abandonUnprepared(tenant, key, "2026-10-14", "CAL");
    assertThat(result.operationStatus()).isEqualTo("PREPARED");
    assertThat(result.snapshotJson()).isEqualTo(prepared.snapshotJson());
  }

  @Test
  @DisplayName("放弃围栏与首次冻结并发时只有一个状态获胜")
  void shouldSerializeAbandonmentAndInitialPreparation() throws Exception {
    String tenant = "fence-race-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var abandoned = executor.submit(() -> {
        ready.countDown();
        go.await();
        return operationStore.abandonUnprepared(tenant, key, "2026-10-14", "CAL");
      });
      var prepared = executor.submit(() -> {
        ready.countDown();
        go.await();
        try {
          return operationStore
              .prepare(new BatchDayCatchUpOperationStore.Prepare(
                  tenant, key, "{}", "{\"targets\":[]}", "[]"))
              .operationStatus();
        } catch (BizException conflict) {
          assertThat(conflict.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          return "CONFLICT";
        }
      });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      String fenceStatus = abandoned.get(15, TimeUnit.SECONDS).operationStatus();
      String prepareStatus = prepared.get(15, TimeUnit.SECONDS);
      assertThat(fenceStatus).isIn("ABANDONED", "PREPARED");
      assertThat(prepareStatus)
          .isEqualTo("ABANDONED".equals(fenceStatus) ? "CONFLICT" : "PREPARED");
      assertThat(operationStore.findByRequestKey(tenant, key).operationStatus())
          .isEqualTo(fenceStatus);
    } finally {
      go.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("同键同请求复用首次候选，同键不同请求返回冲突")
  void shouldFreezeFirstSnapshotAndRejectRequestMismatch() {
    String tenant = "catchup-store-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    String request = "{\"bizDate\":\"2026-10-14\",\"calendarCode\":\"CAL\"}";
    var first = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, key, request, "{\"jobCodes\":[\"A\"]}", "[]"));
    var retry = operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
        tenant, key, request, "{\"jobCodes\":[\"B\"]}", "[]"));

    assertThat(retry.snapshotJson())
        .isEqualTo(first.snapshotJson())
        .contains("A")
        .doesNotContain("B");
    var retryWithoutNewCandidates = operationStore.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, key, request, null, null));
    assertThat(retryWithoutNewCandidates.snapshotJson()).isEqualTo(first.snapshotJson());
    assertThat(operationStore.find(tenant, key, request).snapshotJson())
        .isEqualTo(first.snapshotJson());
    assertThat(operationStore.find("other-tenant", key, request)).isNull();
    assertThatThrownBy(() -> operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
            tenant, key, "{\"bizDate\":\"2026-10-15\",\"calendarCode\":\"CAL\"}", "{}", "[]")))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
  }

  @Test
  @DisplayName("同键并发冻结时两个调用都读取同一份首次快照")
  void shouldReturnOneFrozenSnapshotForConcurrentSameRequest() throws Exception {
    String tenant = "catchup-store-race-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "request-" + UUID.randomUUID();
    String request = "{\"bizDate\":\"2026-10-14\",\"calendarCode\":\"CAL\"}";
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> {
        ready.countDown();
        go.await();
        return operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
            tenant, key, request, "{\"jobCodes\":[\"A\"]}", "[]"));
      });
      var second = executor.submit(() -> {
        ready.countDown();
        go.await();
        return operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
            tenant, key, request, "{\"jobCodes\":[\"B\"]}", "[]"));
      });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      String firstSnapshot = first.get(15, TimeUnit.SECONDS).snapshotJson();
      String secondSnapshot = second.get(15, TimeUnit.SECONDS).snapshotJson();
      assertThat(firstSnapshot).isEqualTo(secondSnapshot);
      assertThat(firstSnapshot).containsAnyOf("A", "B");
    } finally {
      go.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("相同请求键的不同载荷不会覆盖首次冻结的原请求和候选")
  void shouldPreserveFrozenRequestWhenDuplicateKeyHasDifferentPayload() {
    String tenant = "catchup-freeze-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "a".repeat(64);
    BatchDayCatchUpOperationCreate original = BatchDayCatchUpOperationCreate.builder()
        .tenantId(tenant)
        .requestKeyHash(key)
        .requestHash("b".repeat(64))
        .requestJson("{\"reason\":\"original\"}")
        .snapshotJson("{\"jobCodes\":[\"A\"]}")
        .itemsJson("[]")
        .build();
    BatchDayCatchUpOperationCreate different = BatchDayCatchUpOperationCreate.builder()
        .tenantId(tenant)
        .requestKeyHash(key)
        .requestHash("c".repeat(64))
        .requestJson("{\"reason\":\"changed\"}")
        .snapshotJson("{\"jobCodes\":[\"B\"]}")
        .itemsJson("[]")
        .build();

    assertThat(operations.insertIfAbsent(original)).isEqualTo(1);
    assertThat(operations.insertIfAbsent(different)).isZero();
    var persisted = operations.select(tenant, key);
    assertThat(persisted.requestHash()).isEqualTo("b".repeat(64));
    assertThat(persisted.requestJson()).contains("original").doesNotContain("changed");
    assertThat(persisted.snapshotJson()).contains("A").doesNotContain("B");
    assertThat(persisted.operationStatus()).isEqualTo("PREPARED");
    assertThat(persisted.revision()).isZero();
  }

  @Test
  @DisplayName("并发认领仅一方成功，未知结果不能回到运行态")
  void shouldFenceConcurrentClaimAndPreventUnknownResubmission() throws Exception {
    String tenant = "catchup-cas-" + UUID.randomUUID().toString().substring(0, 12);
    String key = "a".repeat(64);
    BatchDayCatchUpOperationCreate operation = BatchDayCatchUpOperationCreate.builder()
        .tenantId(tenant)
        .requestKeyHash(key)
        .requestHash("b".repeat(64))
        .requestJson("{}")
        .snapshotJson("{\"jobCodes\":[\"A\"]}")
        .itemsJson("[]")
        .build();
    assertThat(operations.insertIfAbsent(operation)).isEqualTo(1);
    assertThat(operations.insertIfAbsent(operation)).isZero();
    assertThat(operations.select("other-tenant", key)).isNull();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> {
        ready.countDown();
        go.await();
        return operations.transition(
            new BatchDayCatchUpOperationTransition(tenant, key, 0, "PREPARED", "RUNNING", "[]"));
      });
      var second = executor.submit(() -> {
        ready.countDown();
        go.await();
        return operations.transition(
            new BatchDayCatchUpOperationTransition(tenant, key, 0, "PREPARED", "RUNNING", "[]"));
      });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS))
          .isEqualTo(1);
    } finally {
      go.countDown();
      executor.shutdownNow();
    }
    assertThat(operations.transition(
            new BatchDayCatchUpOperationTransition(tenant, key, 1, "RUNNING", "UNKNOWN", "[]")))
        .isEqualTo(1);
    assertThat(operations.transition(
            new BatchDayCatchUpOperationTransition(tenant, key, 2, "UNKNOWN", "RUNNING", "[]")))
        .isZero();
    var persisted = operations.select(tenant, key);
    assertThat(persisted.operationStatus()).isEqualTo("UNKNOWN");
    assertThat(persisted.revision()).isEqualTo(2);
    assertThat(persisted.snapshotJson()).contains("A");
  }

  @Test
  @DisplayName("请求键在租户内唯一，状态与快照形状受约束且启用强制租户策略")
  void shouldEnforceRequestIdentityAndSnapshotShape() {
    String suffix = UUID.randomUUID().toString().substring(0, 12);
    String firstTenant = "catchup-schema-a-" + suffix;
    String secondTenant = "catchup-schema-b-" + suffix;
    String insertSql = """
        insert into batch.batch_day_catchup_operation
          (tenant_id, request_key_hash, request_hash, request_json, snapshot_json, items_json, operation_status)
        values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
        """;
    String requestKey = "a".repeat(64);
    String requestHash = "b".repeat(64);
    jdbc.update(insertSql, firstTenant, requestKey, requestHash, "{}", "{}", "[]", "PREPARED");
    jdbc.update(insertSql, secondTenant, requestKey, requestHash, "{}", "{}", "[]", "PREPARED");
    assertThatThrownBy(() -> jdbc.update(
            insertSql, firstTenant, requestKey, requestHash, "{}", "{}", "[]", "PREPARED"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(
            insertSql, firstTenant, "c".repeat(64), requestHash, "{}", "{}", "[]", "INVALID"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(
            insertSql, firstTenant, "d".repeat(64), requestHash, "{}", "{}", "{}", "PREPARED"))
        .isInstanceOf(DataIntegrityViolationException.class);
    String policySql = """
        select relrowsecurity, relforcerowsecurity from pg_class
        where oid = 'batch.batch_day_catchup_operation'::regclass
        """;
    Map<String, Object> policy = jdbc.queryForMap(policySql);
    assertThat(policy)
        .containsEntry("relrowsecurity", true)
        .containsEntry("relforcerowsecurity", true);
  }

  @Test
  @DisplayName("非超级用户只能读取和写入当前租户的补跑记录")
  void shouldEnforceTenantRlsForNonSuperuser() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String tenantA = "catchup-rls-a-" + suffix;
    String tenantB = "catchup-rls-b-" + suffix;
    String role = "catchup_test_" + suffix;
    String createRoleSql = "create role " + role + " nologin";
    String grantSchemaSql = "grant usage on schema batch to " + role;
    String grantTableSql = "grant select, insert on batch.batch_day_catchup_operation to " + role;
    String setRoleSql = "set role " + role;
    String setTenantSql = "select set_config('app.tenant_id', ?, false)";
    String selectVisibleSql = "select tenant_id from batch.batch_day_catchup_operation";
    String resetRoleSql = "reset role";
    String resetTenantSql = "reset app.tenant_id";
    String dropGrantsSql = "drop owned by " + role;
    String dropRoleSql = "drop role " + role;
    String insertSql = """
        insert into batch.batch_day_catchup_operation
          (tenant_id, request_key_hash, request_hash, request_json, snapshot_json, items_json, operation_status)
        values (?, ?, ?, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, 'PREPARED')
        """;
    jdbc.update(insertSql, tenantA, "a".repeat(64), "b".repeat(64));
    jdbc.update(insertSql, tenantB, "a".repeat(64), "b".repeat(64));

    jdbc.execute((ConnectionCallback<Void>) connection -> {
      try (var statement = connection.createStatement()) {
        statement.execute(createRoleSql);
        try {
          statement.execute(grantSchemaSql);
          statement.execute(grantTableSql);
          statement.execute(setRoleSql);
          try (var setTenant = connection.prepareStatement(setTenantSql)) {
            setTenant.setString(1, tenantA);
            setTenant.execute();
          }
          try (var rows = statement.executeQuery(selectVisibleSql)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(tenantA);
            assertThat(rows.next()).isFalse();
          }
          try (var insertOther = connection.prepareStatement(insertSql)) {
            insertOther.setString(1, tenantB);
            insertOther.setString(2, "c".repeat(64));
            insertOther.setString(3, "d".repeat(64));
            assertThatThrownBy(insertOther::executeUpdate)
                .isInstanceOfSatisfying(
                    SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
          }
        } finally {
          statement.execute(resetRoleSql);
          statement.execute(resetTenantSql);
          statement.execute(dropGrantsSql);
          statement.execute(dropRoleSql);
        }
      }
      return null;
    });
  }
}
