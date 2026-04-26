package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.application.ConsoleAiApplicationService;
import com.example.batch.console.application.ConsoleApprovalApplicationService;
import com.example.batch.console.application.ConsoleConfigApplicationService;
import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.application.ConsoleFileDownloadApplicationService;
import com.example.batch.console.application.ConsoleJobApprovalService;
import com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.application.ConsoleJobRecoveryService;
import com.example.batch.console.application.ConsoleJobTriggerService;
import com.example.batch.console.application.ConsoleReportExcelApplicationService;
import com.example.batch.console.application.ConsoleWorkerApplicationService;
import com.example.batch.console.application.ConsoleWorkflowExcelApplicationService;
import com.example.batch.console.web.response.AiChatResponse;
import com.example.batch.console.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.web.response.ConsolePresignDownloadResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleHttpIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  private WebTestClient webTestClient;

  @MockitoBean private ConsoleJobTriggerService jobTriggerService;
  @MockitoBean private ConsoleJobRecoveryService jobRecoveryService;
  @MockitoBean private ConsoleJobApprovalService jobApprovalService;

  @MockitoBean
  private ConsoleJobDefinitionExcelApplicationService jobDefinitionExcelApplicationService;

  @MockitoBean private ConsoleWorkerApplicationService workerApplicationService;

  @MockitoBean private ConsoleWorkflowExcelApplicationService workflowExcelApplicationService;

  @MockitoBean private ConsoleApprovalApplicationService approvalApplicationService;

  @MockitoBean private ConsoleFileApplicationService fileApplicationService;

  @MockitoBean private ConsoleConfigApplicationService configApplicationService;

  @MockitoBean private ConsoleReportExcelApplicationService reportExcelApplicationService;

  @MockitoBean private ConsoleAiApplicationService aiApplicationService;

  @MockitoBean private ConsoleFileDownloadApplicationService fileDownloadApplicationService;

  @BeforeEach
  void setUpClient() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
  }

  @Test
  void shouldTriggerJobViaHttp() {
    when(jobTriggerService.trigger(any(), anyString())).thenReturn("job-instance-001");

    webTestClient
        .post()
        .uri("/api/console/jobs/trigger")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-trigger-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","jobCode":"JOB_A","bizDate":"2026-03-27","triggerType":"MANUAL"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"job-instance-001\"");
            });

    verify(jobTriggerService).trigger(any(), anyString());
  }

  @Test
  void shouldExportJobDefinitionExcelViaHttp() {
    byte[] content = "job-definition-excel".getBytes(StandardCharsets.UTF_8);
    when(jobDefinitionExcelApplicationService.exportJobDefinitions(any()))
        .thenReturn(
            ResponseEntity.ok().body(new InputStreamResource(new ByteArrayInputStream(content))));

    webTestClient
        .get()
        .uri("/api/console/config/job-definitions/excel/export?tenantId=tenant-a")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(byte[].class)
        .value(bytes -> assertThat(bytes).isEqualTo(content));
  }

  @Test
  void shouldExportWorkflowExcelViaHttp() {
    byte[] content = "workflow-excel".getBytes(StandardCharsets.UTF_8);
    when(workflowExcelApplicationService.exportWorkflowExcel(any()))
        .thenReturn(
            ResponseEntity.ok().body(new InputStreamResource(new ByteArrayInputStream(content))));

    webTestClient
        .get()
        .uri("/api/console/config/workflows/excel/export?tenantId=tenant-a")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(byte[].class)
        .value(bytes -> assertThat(bytes).isEqualTo(content));
  }

  @Test
  void shouldExportConfigReleasesReportViaHttp() {
    byte[] content = "config-releases-report".getBytes(StandardCharsets.UTF_8);
    when(reportExcelApplicationService.exportConfigReleases(any()))
        .thenReturn(
            ResponseEntity.ok().body(new InputStreamResource(new ByteArrayInputStream(content))));

    webTestClient
        .get()
        .uri("/api/console/reports/excel/config-releases?tenantId=tenant-a")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(byte[].class)
        .value(bytes -> assertThat(bytes).isEqualTo(content));
  }

  @Test
  void shouldReturnValidationErrorWhenJobCodeMissing() {
    webTestClient
        .post()
        .uri("/api/console/jobs/trigger")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-trigger-002")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","bizDate":"2026-03-27"}
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("\"code\":\"VALIDATION_ERROR\""));

    verifyNoInteractions(jobTriggerService);
  }

  @Test
  void shouldDrainWorkerViaHttp() {
    when(workerApplicationService.drain(anyString(), any(), anyString()))
        .thenReturn(
            new ConsoleWorkerRegistryResponse(
                1L,
                "tenant-a",
                "worker-001",
                "group-a",
                null,
                null,
                "DRAINING",
                Instant.now(),
                0,
                null,
                null));

    webTestClient
        .post()
        .uri("/api/console/workers/worker-001/drain")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-worker-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","timeoutSeconds":600}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"status\":\"DRAINING\"");
            });

    verify(workerApplicationService).drain(anyString(), any(), anyString());
  }

  @Test
  void shouldApproveApprovalViaHttp() {
    when(approvalApplicationService.approve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("APPROVED");

    webTestClient
        .post()
        .uri("/api/console/approvals/appr-001/approve")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-approval-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","operatorId":"user-1","reason":"ok"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"APPROVED\"");
            });

    verify(approvalApplicationService).approve("tenant-a", "appr-001", "user-1", "ok");
  }

  @Test
  void shouldArchiveFileViaHttp() {
    when(fileApplicationService.archive(any(), anyString()))
        .thenReturn(new ConsoleFileOperationResponse("ARCHIVED"));

    webTestClient
        .post()
        .uri("/api/console/files/archive")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-file-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","fileId":1001,"reason":"cleanup"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"status\":\"ARCHIVED\"");
            });

    verify(fileApplicationService).archive(any(), anyString());
  }

  @Test
  void shouldPresignDownloadFileViaHttp() {
    when(fileApplicationService.presignDownload(any(), anyString()))
        .thenReturn(new ConsolePresignDownloadResponse("appr-001", null));

    webTestClient
        .post()
        .uri("/api/console/files/presign-download")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-file-002")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","fileId":1001,"reason":"cleanup","approvalId":"appr-001"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"approvalNo\":\"appr-001\"");
            });

    verify(fileApplicationService).presignDownload(any(), anyString());
  }

  @Test
  void shouldRotateSecretViaHttp() {
    when(configApplicationService.rotateSecretVersion(any())).thenReturn(11L);

    webTestClient
        .post()
        .uri("/api/console/config/secrets/rotate")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-config-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","secretRef":"DEFAULT_TEST","secretName":"console-secret","reason":"rotation"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("11");
            });

    verify(configApplicationService).rotateSecretVersion(any());
  }

  @Test
  void shouldChatViaHttp() {
    AiChatResponse chatResponse = new AiChatResponse();
    chatResponse.setRequestId("req-1");
    chatResponse.setTraceId("trace-1");
    chatResponse.setSessionId("session-1");
    chatResponse.setPromptCategory("GENERAL");
    chatResponse.setPromptDecision("APPROVED");
    chatResponse.setModelName("gpt-4o-mini");
    chatResponse.setAnswer("ok");
    when(aiApplicationService.chat(any(), anyString())).thenReturn(chatResponse);

    webTestClient
        .post()
        .uri("/api/console/ai/chat")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ai-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"tenantId":"tenant-a","sessionId":"session-1","prompt":"给我一个调度概览","context":{"topic":"summary"}}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"session-1\"");
              assertThat(body).contains("\"ok\"");
            });

    verify(aiApplicationService).chat(any(), anyString());
  }

  @Test
  void shouldDownloadFileViaHttp() {
    byte[] content = "hello-batch".getBytes(StandardCharsets.UTF_8);
    when(fileDownloadApplicationService.download(anyString(), any(), anyString()))
        .thenReturn(
            ResponseEntity.ok().body(new InputStreamResource(new ByteArrayInputStream(content))));

    webTestClient
        .get()
        .uri("/api/console/files/1001/download?tenantId=tenant-a&approvalId=appr-001")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(byte[].class)
        .value(bytes -> assertThat(bytes).isEqualTo(content));

    verify(fileDownloadApplicationService).download("tenant-a", 1001L, "appr-001");
  }
}
