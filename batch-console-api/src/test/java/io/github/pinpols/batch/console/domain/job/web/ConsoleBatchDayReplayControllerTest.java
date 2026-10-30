package io.github.pinpols.batch.console.domain.job.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayReplayRangeRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayReplaySubmitRequest;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 类型化守护:submit/preview 的 body 反序列化为 {@link BatchDayReplaySubmitRequest}(字段名与 orchestrator
 * BatchDayReplaySubmitCommand 一致),控制器只将类型化请求委托给应用端口。
 */
@DisplayName("批量日重放控制器: 提交与预览的报文绑定及必填字段校验")
@ExtendWith(MockitoExtension.class)
class ConsoleBatchDayReplayControllerTest {

  @Mock
  private ConsoleOrchestratorPort orchestratorProxy;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleBatchDayReplayController(
            orchestratorProxy, responseFactory, requestMetadataResolver))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("提交报文完整绑定为类型化请求后委托下游, 各字段取值与报文一致")
  void shouldDeserializeTypedBodyAndDelegate_whenSubmitRequestComplete() throws Exception {
    when(orchestratorProxy.batchDayReplaySubmit(any(BatchDayReplaySubmitRequest.class)))
        .thenReturn(CommonResponse.success(null));
    mockMvc
        .perform(post("/api/console/ops/batch-day-replay/sessions")
            .header("Idempotency-Key", "idem-1")
            .contentType(APPLICATION_JSON)
            .content("""
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

    ArgumentCaptor<BatchDayReplaySubmitRequest> requestCaptor =
        ArgumentCaptor.forClass(BatchDayReplaySubmitRequest.class);
    verify(orchestratorProxy).batchDayReplaySubmit(requestCaptor.capture());
    BatchDayReplaySubmitRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.getTenantId()).isEqualTo("tb");
    assertThat(forwarded.getCalendarCode()).isEqualTo("CAL-CN");
    assertThat(forwarded.getBizDate()).isEqualTo("2026-07-01");
    assertThat(forwarded.getScope()).isEqualTo("SUBSET_JOB_CODES");
    assertThat(forwarded.getJobCodes()).containsExactly("JOB-A", "JOB-B");
    assertThat(forwarded.getResultPolicy()).isEqualTo("CREATE_NEW_VERSION");
    assertThat(forwarded.getReason()).isEqualTo("rerun after fix");
    assertThat(forwarded.getRequestedBy()).isEqualTo("ops-1");
    assertThat(forwarded.getAutoApprove()).isFalse();
  }

  @Test
  @DisplayName("entry 恢复使用已认证操作者并转发租户、原因")
  void shouldForwardFailedSubmissionRetryWithAuthenticatedOperator() throws Exception {
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata(
            "req-1", "trace-1", "tenant-a", "operator-a", "idem-retry", "127.0.0.1"));
    when(orchestratorProxy.batchDayReplayRetryFailedSubmission(
            31L, 77L, "tenant-a", "operator-a", "confirmed no request was created"))
        .thenReturn(CommonResponse.success(null));

    mockMvc
        .perform(post("/api/console/ops/batch-day-replay/sessions/31/entries/77/retry-submission")
            .header("Idempotency-Key", "idem-retry")
            .queryParam("tenantId", "tenant-a")
            .queryParam("reason", "confirmed no request was created"))
        .andExpect(status().isOk());

    verify(orchestratorProxy)
        .batchDayReplayRetryFailedSubmission(
            31L, 77L, "tenant-a", "operator-a", "confirmed no request was created");
  }

  @Test
  @DisplayName("必填字段缺失时返回参数错误, 且不触达下游端口")
  void shouldRejectSubmit_whenRequiredFieldsMissing() throws Exception {
    // calendarCode/bizDate/scope/reason/requestedBy @NotBlank —— 缺失直接 400,不触达 orchestrator。
    mockMvc
        .perform(post("/api/console/ops/batch-day-replay/sessions")
            .header("Idempotency-Key", "idem-2")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\"}"))
        .andExpect(status().isBadRequest());
    verify(orchestratorProxy, never()).batchDayReplaySubmit(any(BatchDayReplaySubmitRequest.class));
  }

  @Test
  @DisplayName("预览报文绑定后委托下游, 返回成功响应")
  void shouldDelegatePreviewRequest_whenBodyTyped() throws Exception {
    when(orchestratorProxy.batchDayReplayPreview(any(BatchDayReplaySubmitRequest.class)))
        .thenReturn(CommonResponse.success(null));
    mockMvc
        .perform(post("/api/console/ops/batch-day-replay/sessions/preview")
            .contentType(APPLICATION_JSON)
            .content("""
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

    verify(orchestratorProxy).batchDayReplayPreview(any(BatchDayReplaySubmitRequest.class));
  }

  @Test
  @DisplayName("日期区间预览绑定闭区间与范围配置后委托 operation 端口")
  void shouldDelegateDateRangePreview_whenBodyTyped() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/batch-day-replay/operations/date-range/preview")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "calendarCode":"CAL-CN",
                      "fromDate":"2026-07-01",
                      "throughDate":"2026-07-03",
                      "scope":"ALL_FAILED",
                      "reason":"range preview",
                      "requestedBy":"ops-1",
                      "autoApprove":false
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArgumentCaptor<BatchDayReplayRangeRequest> requestCaptor =
        ArgumentCaptor.forClass(BatchDayReplayRangeRequest.class);
    verify(orchestratorProxy).batchDayReplayRangePreview(requestCaptor.capture());
    BatchDayReplayRangeRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.getCalendarCode()).isEqualTo("CAL-CN");
    assertThat(forwarded.getFromDate()).isEqualTo("2026-07-01");
    assertThat(forwarded.getThroughDate()).isEqualTo("2026-07-03");
    assertThat(forwarded.getScope()).isEqualTo("ALL_FAILED");
  }

  @Test
  @DisplayName("区间操作历史使用显式租户和条数委托下游")
  void shouldDelegateOperationHistory_whenTenantAndLimitProvided() throws Exception {
    when(orchestratorProxy.replayOperationList(any(), anyInt()))
        .thenReturn(CommonResponse.success(List.of()));
    mockMvc
        .perform(get("/api/console/ops/batch-day-replay/operations")
            .param("tenantId", "tenant-a")
            .param("limit", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(orchestratorProxy).replayOperationList("tenant-a", 25);
  }

  @Test
  @DisplayName("区间操作详情读取每日子 session 并按租户委托下游")
  void shouldDelegateOperationSessions_whenOperationAndTenantProvided() throws Exception {
    when(orchestratorProxy.replayOperationSessions(any(), any()))
        .thenReturn(CommonResponse.success(List.of()));
    mockMvc
        .perform(get("/api/console/ops/batch-day-replay/operations/70/sessions")
            .param("tenantId", "tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(orchestratorProxy).replayOperationSessions(70L, "tenant-a");
  }

  @Test
  @DisplayName("业务日期格式不合法时返回参数错误, 且不触达下游端口")
  void shouldRejectSubmit_whenBizDateMalformed() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/batch-day-replay/sessions")
            .header("Idempotency-Key", "idem-3")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "calendarCode":"CAL-CN",
                      "bizDate":"2026/07/01",
                      "scope":"ALL",
                      "reason":"bad date",
                      "requestedBy":"ops-1"
                    }
                    """))
        .andExpect(status().isBadRequest());
    verify(orchestratorProxy, never()).batchDayReplaySubmit(any(BatchDayReplaySubmitRequest.class));
  }
}
