package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.web.response.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowTopologyResponse;
import com.example.batch.common.model.PageResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleQueryControllerTest {

    private final ConsoleQueryApplicationService queryApplicationService = org.mockito.Mockito.mock(ConsoleQueryApplicationService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleQueryController(queryApplicationService, responseFactory))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldReturnApprovalDtos() throws Exception {
        when(queryApplicationService.approvals(any())).thenReturn(new PageResponse<>(1L, 1, 20, List.of(
                new ConsoleApprovalCommandResponse(1L, "t1", "appr-001", "DOWNLOAD", "DOWNLOAD", "FILE", "1001",
                        "{}", "PENDING", "req-1", null, null, null, "trace-1", "idem-1",
                        OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC), null,
                        OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
        )));

        mockMvc.perform(get("/api/console/query/approvals").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].approvalNo").value("appr-001"))
                .andExpect(jsonPath("$.data.items[0].approvalStatus").value("PENDING"));
    }

    @Test
    void shouldReturnAlertDtos() throws Exception {
        when(queryApplicationService.alertEvents(any())).thenReturn(new PageResponse<>(1L, 1, 20, List.of(
                new ConsoleAlertEventResponse(
                        1L,
                        "t1",
                        "console-api",
                        "FILE_ERROR",
                        "HIGH",
                        "file failed",
                        "{\"k\":\"v\"}",
                        "dedup-1",
                        2,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "trace-1",
                        "OPEN",
                        Instant.EPOCH,
                        Instant.EPOCH
                )
        )));

        mockMvc.perform(get("/api/console/query/alerts").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].title").value("file failed"));
    }

    @Test
    void shouldReturnExecutionLogDtos() throws Exception {
        when(queryApplicationService.executionLogs(any())).thenReturn(new PageResponse<>(1L, 1, 20, List.of(
                new ConsoleAuditLogResponse(
                        1L,
                        "t1",
                        1001L,
                        "FILE_UPLOAD",
                        "SUCCESS",
                        "OPERATOR",
                        "u1",
                        "trace-1",
                        "evidence-1",
                        "summary",
                        Instant.EPOCH
                )
        )));

        mockMvc.perform(get("/api/console/query/execution-logs").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].operationType").value("FILE_UPLOAD"));
    }

    @Test
    void shouldReturnFileChainsPage() throws Exception {
        when(queryApplicationService.fileChains(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/files").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFilePipelinesPage() throws Exception {
        when(queryApplicationService.filePipelines(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-pipelines").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnLegacyPipelineDefinitionsPage() throws Exception {
        when(queryApplicationService.filePipelines(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/pipeline-definitions").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFilePipelineStepsPage() throws Exception {
        when(queryApplicationService.filePipelineSteps(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-pipeline-steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFileDispatchesPage() throws Exception {
        when(queryApplicationService.fileDispatchRecords(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-dispatches").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFileChannelsPage() throws Exception {
        when(queryApplicationService.fileChannels(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-channels").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFileArrivalGroupsPage() throws Exception {
        when(queryApplicationService.fileArrivalGroups(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-arrival-groups").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFileErrorsPage() throws Exception {
        when(queryApplicationService.fileErrorRecords(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-errors").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnFileTemplatesPage() throws Exception {
        when(queryApplicationService.fileTemplates(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/file-templates").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnWorkflowDefinitionsPage() throws Exception {
        when(queryApplicationService.workflowDefinitions(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/workflow-definitions").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnWorkflowNodesPage() throws Exception {
        when(queryApplicationService.workflowNodes(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/workflow-nodes").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnWorkflowEdgesPage() throws Exception {
        when(queryApplicationService.workflowEdges(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/workflow-edges").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnWorkflowTopology() throws Exception {
        when(queryApplicationService.workflowTopology(any())).thenReturn(
                new ConsoleWorkflowTopologyResponse(null, List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/console/query/workflow-topology").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nodes").isArray())
                .andExpect(jsonPath("$.data.edges").isArray());
    }

    @Test
    void shouldReturnLegacyPipelineDefinitionsDetail() throws Exception {
        when(queryApplicationService.filePipelineDetail(anyString(), anyLong())).thenReturn(new ConsoleFilePipelineResponse(
                1L,
                "t1",
                1001L,
                "file-001",
                "IMPORT",
                2001L,
                3001L,
                "RECEIVE",
                "PARSE",
                "SUCCESS",
                "trace-1",
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH
        ));

        mockMvc.perform(get("/api/console/query/pipeline-definitions/1").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobCode").value("file-001"))
                .andExpect(jsonPath("$.data.runStatus").value("SUCCESS"));
    }

    @Test
    void shouldReturnAiAuditsPage() throws Exception {
        when(queryApplicationService.aiAuditLogs(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/ai-audits").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnOutboxRetriesPage() throws Exception {
        when(queryApplicationService.outboxRetries(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/outbox-retries").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnOutboxDeliveriesPage() throws Exception {
        when(queryApplicationService.outboxDeliveries(any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/console/query/outbox-deliveries").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturnJobInstanceDetail() throws Exception {
        when(queryApplicationService.jobInstance(anyString(), anyLong())).thenReturn(new ConsoleJobInstanceResponse(
                11L,
                "t1",
                "job-001",
                "inst-001",
                java.time.LocalDate.of(2026, 3, 29),
                "MANUAL",
                "SUCCESS",
                "batch-1",
                "operator-1",
                false,
                false,
                null,
                null,
                null,
                "queue-1",
                "worker-group-1",
                5,
                "trace-1",
                "{}",
                "ok",
                Instant.EPOCH,
                3600,
                null,
                Instant.EPOCH,
                Instant.EPOCH
        ));

        mockMvc.perform(get("/api/console/query/instances/11").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("inst-001"))
                .andExpect(jsonPath("$.data.instanceStatus").value("SUCCESS"));
    }

    @Test
    void shouldReturnJobStepInstanceDetail() throws Exception {
        when(queryApplicationService.jobStepInstance(anyString(), anyLong())).thenReturn(new ConsoleJobStepInstanceResponse(
                21L,
                "t1",
                11L,
                1L,
                1001L,
                "step-1",
                "MAIN",
                "SUCCESS",
                0,
                null,
                "ok",
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH
        ));

        mockMvc.perform(get("/api/console/query/job-step-instances/21").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stepCode").value("step-1"))
                .andExpect(jsonPath("$.data.stepStatus").value("SUCCESS"));
    }

    @Test
    void shouldReturnWorkflowRunDetail() throws Exception {
        when(queryApplicationService.workflowRun(anyString(), anyLong())).thenReturn(new ConsoleWorkflowRunResponse(
                31L,
                "t1",
                100L,
                11L,
                java.time.LocalDate.of(2026, 3, 29),
                "RUNNING",
                "node-1",
                "trace-1",
                Instant.EPOCH,
                null,
                Instant.EPOCH,
                Instant.EPOCH
        ));

        mockMvc.perform(get("/api/console/query/workflow-runs/31").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.currentNodeCode").value("node-1"));
    }

    @Test
    void shouldReturnWorkflowNodeRunDetail() throws Exception {
        when(queryApplicationService.workflowNodeRun(anyString(), anyLong())).thenReturn(new ConsoleWorkflowNodeRunResponse(
                41L,
                31L,
                "node-1",
                "MAIN",
                1,
                "SUCCESS",
                0,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                120L
        ));

        mockMvc.perform(get("/api/console/query/workflow-node-runs/41").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeCode").value("node-1"))
                .andExpect(jsonPath("$.data.nodeStatus").value("SUCCESS"));
    }

    @Test
    void shouldReturnBatchInstanceStatus() throws Exception {
        when(queryApplicationService.batchInstanceStatus(anyString(), any())).thenReturn(List.of(
                new ConsoleJobInstanceResponse(
                        11L,
                        "t1",
                        "IMPORT_JOB",
                        "INS-001",
                        java.time.LocalDate.of(2026, 3, 29),
                        "MANUAL",
                        "RUNNING",
                        "batch-1",
                        "operator-1",
                        false,
                        false,
                        null,
                        null,
                        null,
                        "queue-1",
                        "worker-group-1",
                        5,
                        "trace-1",
                        "{}",
                        "running",
                        Instant.EPOCH,
                        3600,
                        null,
                        Instant.EPOCH,
                        null
                )
        ));

        mockMvc.perform(get("/api/console/query/instances/batch-status")
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
