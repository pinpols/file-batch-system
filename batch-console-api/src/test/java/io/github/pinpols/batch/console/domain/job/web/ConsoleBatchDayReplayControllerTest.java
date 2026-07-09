package io.github.pinpols.batch.console.domain.job.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.job.web.request.BatchDayReplaySubmitRequest;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * 类型化守护:submit/preview 的 body 反序列化为 {@link BatchDayReplaySubmitRequest}(字段名与 orchestrator
 * BatchDayReplaySubmitCommand 一致),tenantId 经 guard 解析后强制覆盖再转发。
 */
class ConsoleBatchDayReplayControllerTest {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient =
      mock(OrchestratorInternalRestClient.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);

  private RestClient restClient;
  private RestClient.RequestBodyUriSpec bodyUriSpec;
  private RestClient.RequestBodySpec bodySpec;
  private RestClient.ResponseSpec responseSpec;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    restClient = mock(RestClient.class);
    bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    bodySpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);
    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    when(restClient.post()).thenReturn(bodyUriSpec);
    when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenReturn(
            Map.of(
                "success",
                true,
                "code",
                "SUCCESS",
                "data",
                Map.of("id", 1, "status", "PENDING_APPROVAL")));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleBatchDayReplayController(
                    orchestratorInternalRestClient, tenantGuard, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void submitShouldDeserializeTypedBodyAndOverwriteTenant() throws Exception {
    when(tenantGuard.resolveTenant("tb")).thenReturn("ta");

    mockMvc
        .perform(
            post("/api/console/ops/batch-day-replay/sessions")
                .header("Idempotency-Key", "idem-1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId":"tb",
                      "calendarCode":"CAL-CN",
                      "bizDate":"2026-07-01",
                      "scope":"SUBSET_JOB_CODES",
                      "jobCodes":["JOB-A","JOB-B"],
                      "resultPolicy":"CREATE_NEW_VERSION",
                      "reason":"rerun after fix",
                      "requestedBy":"ops-1",
                      "autoApprove":false
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
    verify(bodySpec).body(bodyCaptor.capture());
    BatchDayReplaySubmitRequest forwarded = (BatchDayReplaySubmitRequest) bodyCaptor.getValue();
    assertThat(forwarded.getTenantId()).isEqualTo("ta");
    assertThat(forwarded.getCalendarCode()).isEqualTo("CAL-CN");
    assertThat(forwarded.getBizDate()).isEqualTo("2026-07-01");
    assertThat(forwarded.getScope()).isEqualTo("SUBSET_JOB_CODES");
    assertThat(forwarded.getJobCodes()).containsExactly("JOB-A", "JOB-B");
    assertThat(forwarded.getResultPolicy()).isEqualTo("CREATE_NEW_VERSION");
    assertThat(forwarded.getReason()).isEqualTo("rerun after fix");
    assertThat(forwarded.getRequestedBy()).isEqualTo("ops-1");
    assertThat(forwarded.getAutoApprove()).isFalse();
    verify(bodyUriSpec).uri("/internal/orchestrator/batch-day-replay/sessions");
  }

  @Test
  void submitShouldRejectMissingRequiredFieldsWith400() throws Exception {
    // calendarCode/bizDate/scope/reason/requestedBy @NotBlank —— 缺失直接 400,不触达 orchestrator。
    mockMvc
        .perform(
            post("/api/console/ops/batch-day-replay/sessions")
                .header("Idempotency-Key", "idem-2")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\"}"))
        .andExpect(status().isBadRequest());
    verify(orchestratorInternalRestClient, never()).build();
  }

  @Test
  void previewShouldForwardWithResolvedTenant() throws Exception {
    when(tenantGuard.resolveTenant(null)).thenReturn("ta");

    mockMvc
        .perform(
            post("/api/console/ops/batch-day-replay/sessions/preview")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "calendarCode":"CAL-CN",
                      "bizDate":"2026-07-01",
                      "scope":"ALL_FAILED",
                      "reason":"preview impact",
                      "requestedBy":"ops-1"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
    verify(bodySpec).body(bodyCaptor.capture());
    BatchDayReplaySubmitRequest forwarded = (BatchDayReplaySubmitRequest) bodyCaptor.getValue();
    assertThat(forwarded.getTenantId()).isEqualTo("ta");
    verify(bodyUriSpec).uri("/internal/orchestrator/batch-day-replay/sessions/preview");
  }

  @Test
  void submitShouldRejectInvalidBizDateWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ops/batch-day-replay/sessions")
                .header("Idempotency-Key", "idem-3")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "calendarCode":"CAL-CN",
                      "bizDate":"2026/07/01",
                      "scope":"ALL",
                      "reason":"bad date",
                      "requestedBy":"ops-1"
                    }
                    """))
        .andExpect(status().isBadRequest());
    verify(orchestratorInternalRestClient, never()).build();
  }
}
