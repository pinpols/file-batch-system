package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApplicationService;
import io.github.pinpols.batch.console.application.contract.response.file.ConsolePresignDownloadResponse;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiApplicationService;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiChatResponse;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileDownloadApplicationService;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileOperationResponse;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobTriggerService;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleReportExcelApplicationService;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleApprovalApplicationService;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleWorkerApplicationService;
import io.github.pinpols.batch.console.shared.view.ConsoleWorkerRegistryResponse;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
@DisplayName("控制台 HTTP 写端点: 触发,导出,排水,审批,文件与密钥等链路的响应与入参校验")
class ConsoleHttpIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort
  private int port;

  private WebTestClient webTestClient;

  @MockitoBean
  private ConsoleJobTriggerService jobTriggerService;

  @MockitoBean
  private ConsoleJobRecoveryService jobRecoveryService;

  @MockitoBean
  private ConsoleJobApprovalService jobApprovalService;

  @MockitoBean
  private ConsoleWorkerApplicationService workerApplicationService;

  @MockitoBean
  private ConsoleApprovalApplicationService approvalApplicationService;

  @MockitoBean
  private ConsoleFileApplicationService fileApplicationService;

  @MockitoBean
  private ConsoleConfigApplicationService configApplicationService;

  @MockitoBean
  private ConsoleReportExcelApplicationService reportExcelApplicationService;

  @MockitoBean
  private ConsoleAiApplicationService aiApplicationService;

  @MockitoBean
  private ConsoleAiAuthorizationService aiAuthorizationService;

  @MockitoBean
  private ConsoleFileDownloadApplicationService fileDownloadApplicationService;

  @BeforeEach
  void setUpClient() {
    // 用 JDK HttpClient 连接器而非默认 reactor-netty:后者的 HTTP 解码器对「重复且相同」的
    // Content-Length 响应头 fail-fast 抛 "Multiple Content-Length values found"(Netty 安全加固),
    // 在 CI runner(JDK25 + Tomcat 响应管线)上偶发触发,与被测业务无关。JDK 连接器对此宽容,
    // 消除该环境 flake;不改任何生产行为,仅约束测试客户端。
    webTestClient = WebTestClient.bindToServer(new JdkClientHttpConnector())
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
  }

  @Test
  @DisplayName("触发作业: 返回成功并回显实例编号,应用服务被调用一次")
  void shouldTriggerJobViaHttp() {
    when(jobTriggerService.trigger(any(), anyString())).thenReturn("job-instance-001");

    webTestClient
        .post()
        .uri("/api/console/jobs/trigger")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-trigger-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","jobCode":"JOB_A","bizDate":"2026-03-27","triggerType":"MANUAL"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"job-instance-001\"");
        });

    verify(jobTriggerService).trigger(any(), anyString());
  }

  @Test
  @DisplayName("导出配置发布报表: 返回成功,响应体字节与导出内容一致")
  void shouldExportConfigReleasesReportViaHttp() {
    byte[] content = "config-releases-report".getBytes(StandardCharsets.UTF_8);
    // R2-P1-9: 应用服务签名已切到 StreamingResponseBody，直接 lambda 写响应流
    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
        out -> out.write(content);
    when(reportExcelApplicationService.exportConfigReleases(any()))
        .thenReturn(ResponseEntity.ok().body(body));

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
  @DisplayName("缺少作业编码: 返回校验错误且不触达应用服务")
  void shouldReturnValidationErrorWhenJobCodeMissing() {
    webTestClient
        .post()
        .uri("/api/console/jobs/trigger")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-trigger-002")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
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
  @DisplayName("排水工作节点: 返回成功且响应体状态为排水中,应用服务被调用")
  void shouldDrainWorkerViaHttp() {
    when(workerApplicationService.drain(anyString(), any(), anyString()))
        .thenReturn(new ConsoleWorkerRegistryResponse(
            1L,
            "tenant-a",
            "worker-001",
            "group-a",
            null,
            null,
            "DRAINING",
            BatchDateTimeSupport.utcNow(),
            0,
            null,
            null,
            null));

    webTestClient
        .post()
        .uri("/api/console/workers/worker-001/drain")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-worker-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","timeoutSeconds":600}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"status\":\"DRAINING\"");
        });

    verify(workerApplicationService).drain(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("审批通过: 返回成功并回显审批结果,应用服务按请求入参被调用")
  void shouldApproveApprovalViaHttp() {
    when(approvalApplicationService.approve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("APPROVED");

    webTestClient
        .post()
        .uri("/api/console/approvals/appr-001/approve")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-approval-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","operatorId":"user-1","reason":"ok"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"APPROVED\"");
        });

    verify(approvalApplicationService).approve("tenant-a", "appr-001", "user-1", "ok");
  }

  @Test
  @DisplayName("归档文件: 返回成功且响应体状态为已归档")
  void shouldArchiveFileViaHttp() {
    when(fileApplicationService.archive(any(), anyString()))
        .thenReturn(new ConsoleFileOperationResponse(FileStatus.ARCHIVED.code()));

    webTestClient
        .post()
        .uri("/api/console/files/archive")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-file-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","fileId":1001,"reason":"cleanup"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"status\":\"ARCHIVED\"");
        });

    verify(fileApplicationService).archive(any(), anyString());
  }

  @Test
  @DisplayName("预签名下载: 返回成功并回显审批单号")
  void shouldPresignDownloadFileViaHttp() {
    when(fileApplicationService.presignDownload(any(), anyString()))
        .thenReturn(new ConsolePresignDownloadResponse("appr-001", null));

    webTestClient
        .post()
        .uri("/api/console/files/presign-download")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-file-002")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","fileId":1001,"reason":"cleanup","approvalId":"appr-001"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"approvalNo\":\"appr-001\"");
        });

    verify(fileApplicationService).presignDownload(any(), anyString());
  }

  @Test
  @DisplayName("轮换密钥: 返回成功并在响应体带出新版本号")
  void shouldRotateSecretViaHttp() {
    when(configApplicationService.rotateSecretVersion(any())).thenReturn(11L);

    webTestClient
        .post()
        .uri("/api/console/config/secrets/rotate")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-config-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","secretRef":"DEFAULT_TEST","secretName":"console-secret","reason":"rotation"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("11");
        });

    verify(configApplicationService).rotateSecretVersion(any());
  }

  @Test
  @DisplayName("流式对话: 依次推送开始,增量与完成事件,会话标识与增量内容可见")
  void shouldStreamChatViaHttp() {
    AiChatResponse chatResponse = new AiChatResponse();
    chatResponse.setRequestId("req-1");
    chatResponse.setTraceId("trace-1");
    chatResponse.setSessionId("session-1");
    chatResponse.setPromptCategory("GENERAL");
    chatResponse.setPromptDecision("APPROVED");
    chatResponse.setModelName("gpt-4o-mini");
    chatResponse.setAnswer("ok");
    when(aiApplicationService.chatStream(any(), anyString(), any(), any()))
        .thenAnswer(invocation -> {
          ConsoleAiApplicationService.StreamObserver observer = invocation.getArgument(3);
          observer.onDelta("ok");
          return chatResponse;
        });

    webTestClient
        .post()
        .uri("/api/console/ai/chat/stream")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ai-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"tenant-a","sessionId":"session-1","prompt":"给我一个调度概览","context":{"topic":"summary"}}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("event:started");
          assertThat(body).contains("event:delta");
          assertThat(body).contains("event:completed");
          assertThat(body).contains("\"session-1\"");
          assertThat(body).contains("\"ok\"");
        });

    verify(aiApplicationService).chatStream(any(), anyString(), any(), any());
  }

  @Test
  @DisplayName("下载文件: 返回成功,响应体内容与文件一致,应用服务按入参被调用")
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
