package io.github.pinpols.batch.console.domain.observability.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.observability.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobStepInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowTopologyResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.view.ConsoleApprovalCommandResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleAuditLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxRetryLogResponse;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.ConsoleQueryController;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("控制台查询接口:分页返回、详情返回与参数校验")
class ConsoleQueryControllerTest {

  private final ConsoleQueryApplicationService queryApplicationService =
      mock(ConsoleQueryApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    // ConsoleQueryController 构造参数:applicationService / responseFactory /
    // operationAuditQueryService / orchestratorProxy。本测试只覆盖 query 路径,其余 null。
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleQueryController(queryApplicationService, responseFactory, null, null))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("旧Pipeline查询别名完全移除，不重定向或返回文件运行数据")
  void shouldNotExposeRetiredPipelineQueryAliases() throws Exception {
    mockMvc
        .perform(get("/api/console/queries/pipeline-definitions").param("tenantId", "t1"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/console/queries/pipeline-definitions/1").param("tenantId", "t1"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("流水线进度查询:已下线的 Worker 编码参数被拒绝")
  void shouldRejectRemovedWorkerCodeProgressQuery() throws Exception {
    mockMvc
        .perform(get("/api/console/queries/pipeline-progress")
            .param("tenantId", "t1")
            .param("workerCodes", "worker-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("审批查询:分页返回审批单号与审批状态")
  void shouldReturnApprovalDtos() throws Exception {
    when(queryApplicationService.approvals(any()))
        .thenReturn(new PageResponse<>(
            1L,
            1,
            20,
            List.of(ConsoleApprovalCommandResponse.builder()
                .id(1L)
                .tenantId("t1")
                .approvalNo("appr-001")
                .approvalType("DOWNLOAD")
                .actionType("DOWNLOAD")
                .targetType("FILE")
                .targetId("1001")
                .payloadJson("{}")
                .approvalStatus("PENDING")
                .requesterId("req-1")
                .sourceTraceId("trace-1")
                .sourceIdempotencyKey("idem-1")
                .approvedAt(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
                .createdAt(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
                .build()));

    mockMvc
        .perform(get("/api/console/queries/approvals").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].approvalNo").value("appr-001"))
        .andExpect(jsonPath("$.data.items[0].approvalStatus").value("PENDING"));
  }

  @Test
  @DisplayName("告警查询:分页返回告警等级与标题")
  void shouldReturnAlertDtos() throws Exception {
    when(queryApplicationService.alertEvents(any()))
        .thenReturn(new PageResponse<>(
            1L,
            1,
            20,
            List.of(ConsoleAlertEventResponse.builder()
                .id(1L)
                .tenantId("t1")
                .serviceName("console-api")
                .alertType("FILE_ERROR")
                .severity("HIGH")
                .title("file failed")
                .detailJson("{\"k\":\"v\"}")
                .dedupFingerprint("dedup-1")
                .occurrenceCount(2)
                .firstSeenAt(Instant.EPOCH)
                .lastSeenAt(Instant.EPOCH)
                .traceId("trace-1")
                .status("OPEN")
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .build()));

    mockMvc
        .perform(get("/api/console/queries/alerts").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].severity").value("HIGH"))
        .andExpect(jsonPath("$.data.items[0].title").value("file failed"));
  }

  @Test
  @DisplayName("执行日志查询:分页返回操作类型等审计字段")
  void shouldReturnExecutionLogDtos() throws Exception {
    when(queryApplicationService.executionLogs(any()))
        .thenReturn(new PageResponse<>(
            1L,
            1,
            20,
            List.of(ConsoleAuditLogResponse.builder()
                .id(1L)
                .tenantId("t1")
                .fileId(1001L)
                .operationType("FILE_UPLOAD")
                .operationResult("SUCCESS")
                .operatorType("OPERATOR")
                .operatorId("u1")
                .traceId("trace-1")
                .evidenceRef("evidence-1")
                .detailSummary("summary")
                .createdAt(Instant.EPOCH)
                .build()));

    mockMvc
        .perform(get("/api/console/queries/execution-logs").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].operationType").value("FILE_UPLOAD"));
  }

  @Test
  @DisplayName("文件链路查询:返回空分页且总数为 0")
  void shouldReturnFileChainsPage() throws Exception {
    when(queryApplicationService.fileChains(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/files").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件流水线查询:关键字参数透传后返回分页")
  void shouldReturnFilePipelinesPage() throws Exception {
    when(queryApplicationService.filePipelines(
            argThat(request -> "failed".equals(request.getKeyword()))))
        .thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-pipelines")
            .param("tenantId", "t1")
            .param("keyword", "failed"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件流水线步骤查询:关键字参数透传后返回分页")
  void shouldReturnFilePipelineStepsPage() throws Exception {
    when(queryApplicationService.filePipelineSteps(
            argThat(request -> "parse".equals(request.getKeyword()))))
        .thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-pipeline-steps").param("keyword", "parse"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件分发记录查询:关键字参数透传后返回分页")
  void shouldReturnFileDispatchesPage() throws Exception {
    when(queryApplicationService.fileDispatchRecords(
            argThat(request -> "sent".equals(request.getKeyword()))))
        .thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-dispatches")
            .param("tenantId", "t1")
            .param("keyword", "sent"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件通道查询:返回分页且总数为 0")
  void shouldReturnFileChannelsPage() throws Exception {
    when(queryApplicationService.fileChannels(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-channels").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件到达组查询:返回分页且总数为 0")
  void shouldReturnFileArrivalGroupsPage() throws Exception {
    when(queryApplicationService.fileArrivalGroups(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-arrival-groups").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件错误记录查询:关键字参数透传后返回分页")
  void shouldReturnFileErrorsPage() throws Exception {
    when(queryApplicationService.fileErrorRecords(
            argThat(request -> "invalid".equals(request.getKeyword()))))
        .thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-errors")
            .param("tenantId", "t1")
            .param("keyword", "invalid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("文件模板查询:返回分页且总数为 0")
  void shouldReturnFileTemplatesPage() throws Exception {
    when(queryApplicationService.fileTemplates(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/file-templates").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("工作流定义查询:返回分页且总数为 0")
  void shouldReturnWorkflowDefinitionsPage() throws Exception {
    when(queryApplicationService.workflowDefinitions(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/workflow-definitions").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("工作流节点查询:返回分页且总数为 0")
  void shouldReturnWorkflowNodesPage() throws Exception {
    when(queryApplicationService.workflowNodes(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/workflow-nodes").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("工作流连线查询:返回分页且总数为 0")
  void shouldReturnWorkflowEdgesPage() throws Exception {
    when(queryApplicationService.workflowEdges(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/workflow-edges").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("工作流拓扑查询:返回节点与连线数组")
  void shouldReturnWorkflowTopology() throws Exception {
    when(queryApplicationService.workflowTopology(any()))
        .thenReturn(
            new ConsoleWorkflowTopologyResponse(null, List.of(), List.of(), List.of(), List.of()));

    mockMvc
        .perform(get("/api/console/queries/workflow-topology").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.nodes").isArray())
        .andExpect(jsonPath("$.data.edges").isArray());
  }

  @Test
  @DisplayName("AI 审计查询:返回分页且总数为 0")
  void shouldReturnAiAuditsPage() throws Exception {
    when(queryApplicationService.aiAuditLogs(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/ai-audits").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("发件箱重试查询:分页返回重试记录标识与事件编号")
  void shouldReturnOutboxRetriesPage() throws Exception {
    when(queryApplicationService.outboxRetries(any()))
        .thenReturn(new PageResponse<>(
            1,
            1,
            20,
            List.of(ConsoleOutboxRetryLogResponse.builder()
                .id(31L)
                .outboxEventId(41L)
                .tenantId("t1")
                .eventType("JOB_FAILED")
                .eventKey("event-1")
                .retryStatus("FAILED")
                .retryCount(2)
                .retryPolicy("EXPONENTIAL")
                .nextRetryAt(Instant.EPOCH)
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .build()));

    mockMvc
        .perform(get("/api/console/queries/outbox-retries").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(31))
        .andExpect(jsonPath("$.data.items[0].outboxEventId").value(41));
  }

  @Test
  @DisplayName("发件箱投递查询:返回分页且总数为 0")
  void shouldReturnOutboxDeliveriesPage() throws Exception {
    when(queryApplicationService.outboxDeliveries(any())).thenReturn(emptyPage());

    mockMvc
        .perform(get("/api/console/queries/outbox-deliveries").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("作业实例详情:返回实例编号与实例状态")
  void shouldReturnJobInstanceDetail() throws Exception {
    when(queryApplicationService.jobInstance(anyString(), anyLong()))
        .thenReturn(ConsoleJobInstanceResponse.builder()
            .id(11L)
            .tenantId("t1")
            .jobCode("job-001")
            .instanceNo("inst-001")
            .bizDate(LocalDate.of(2026, Month.MARCH, 29))
            .triggerType("MANUAL")
            .instanceStatus("SUCCESS")
            .batchNo("batch-1")
            .operatorId("operator-1")
            .rerunFlag(false)
            .retryFlag(false)
            .queueCode("queue-1")
            .workerGroup("worker-group-1")
            .priority(5)
            .traceId("trace-1")
            .paramsSnapshot("{}")
            .resultSummary("ok")
            .deadlineAt(Instant.EPOCH)
            .expectedDurationSeconds(3600)
            .startedAt(Instant.EPOCH)
            .finishedAt(Instant.EPOCH)
            .dryRun(false)
            .build());

    mockMvc
        .perform(get("/api/console/queries/instances/11").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.instanceNo").value("inst-001"))
        .andExpect(jsonPath("$.data.instanceStatus").value("SUCCESS"));
  }

  @Test
  @DisplayName("作业步骤实例详情:返回步骤编码与步骤状态")
  void shouldReturnJobStepInstanceDetail() throws Exception {
    when(queryApplicationService.jobStepInstance(anyString(), anyLong()))
        .thenReturn(ConsoleJobStepInstanceResponse.builder()
            .id(21L)
            .tenantId("t1")
            .jobInstanceId(11L)
            .jobPartitionId(1L)
            .jobTaskId(1001L)
            .stepCode("step-1")
            .stepType("MAIN")
            .stepStatus("SUCCESS")
            .retryCount(0)
            .resultSummary("ok")
            .startedAt(Instant.EPOCH)
            .finishedAt(Instant.EPOCH)
            .build());

    mockMvc
        .perform(get("/api/console/queries/job-step-instances/21").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.stepCode").value("step-1"))
        .andExpect(jsonPath("$.data.stepStatus").value("SUCCESS"));
  }

  @Test
  @DisplayName("工作流运行详情:返回运行状态与当前节点")
  void shouldReturnWorkflowRunDetail() throws Exception {
    when(queryApplicationService.workflowRun(anyString(), anyLong()))
        .thenReturn(new ConsoleWorkflowRunResponse(
            31L,
            "t1",
            100L,
            11L,
            LocalDate.of(2026, Month.MARCH, 29),
            "RUNNING",
            "node-1",
            "trace-1",
            Instant.EPOCH,
            null,
            Instant.EPOCH,
            Instant.EPOCH));

    mockMvc
        .perform(get("/api/console/queries/workflow-runs/31").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.runStatus").value("RUNNING"))
        .andExpect(jsonPath("$.data.currentNodeCode").value("node-1"));
  }

  @Test
  @DisplayName("工作流节点运行详情:返回节点编码与节点状态")
  void shouldReturnWorkflowNodeRunDetail() throws Exception {
    when(queryApplicationService.workflowNodeRun(anyString(), anyLong()))
        .thenReturn(new ConsoleWorkflowNodeRunResponse(
            41L,
            31L,
            null,
            "node-1",
            "MAIN",
            1,
            "SUCCESS",
            0,
            null,
            null,
            null,
            null,
            Instant.EPOCH,
            Instant.EPOCH,
            120L));

    mockMvc
        .perform(get("/api/console/queries/workflow-node-runs/41").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.nodeCode").value("node-1"))
        .andExpect(jsonPath("$.data.nodeStatus").value("SUCCESS"));
  }

  @Test
  @DisplayName("批量实例状态查询:按多个实例编号返回实例编号与实例状态")
  void shouldReturnBatchInstanceStatus() throws Exception {
    when(queryApplicationService.batchInstanceStatus(anyString(), any()))
        .thenReturn(List.of(ConsoleJobInstanceResponse.builder()
            .id(11L)
            .tenantId("t1")
            .jobCode("IMPORT_JOB")
            .instanceNo("INS-001")
            .bizDate(LocalDate.of(2026, Month.MARCH, 29))
            .triggerType("MANUAL")
            .instanceStatus("RUNNING")
            .batchNo("batch-1")
            .operatorId("operator-1")
            .rerunFlag(false)
            .retryFlag(false)
            .queueCode("queue-1")
            .workerGroup("worker-group-1")
            .priority(5)
            .traceId("trace-1")
            .paramsSnapshot("{}")
            .resultSummary("running")
            .deadlineAt(Instant.EPOCH)
            .expectedDurationSeconds(3600)
            .startedAt(Instant.EPOCH)
            .dryRun(false)
            .build()));

    mockMvc
        .perform(get("/api/console/queries/instances/batch-status")
            .param("tenantId", "t1")
            .param("instanceNos", "INS-001", "INS-002"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].instanceNo").value("INS-001"))
        .andExpect(jsonPath("$.data[0].instanceStatus").value("RUNNING"));
  }

  private <T> PageResponse<T> emptyPage() {
    return new PageResponse<>(0L, 1, 20, List.of());
  }
}
