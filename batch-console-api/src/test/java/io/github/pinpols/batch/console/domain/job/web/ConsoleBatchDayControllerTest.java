package io.github.pinpols.batch.console.domain.job.web;

import java.util.Objects;
import io.github.pinpols.batch.console.application.ops.command.BatchDayOperationCommand;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayCatchupPreviewResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayHistoricalGapInventoryResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayOperateResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleBatchDayController operate 转发到 orchestrator + action 枚举校验。 */
@DisplayName("批量日操作控制器: 请求字段透传与动作白名单拦截")
class ConsoleBatchDayControllerTest {

  private final ConsoleOrchestratorPort proxy = mock(ConsoleOrchestratorPort.class);
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
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", "ta", "ops-user", null, null));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleBatchDayController(proxy, responseFactory, requestMetadataResolver))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("合法的批量日操作请求逐字段透传到下游端口, 业务日期按原值解析")
  void shouldForwardAllFieldsToOrchestrator_whenRequestValid() throws Exception {
    when(proxy.batchDayOperate(org.mockito.ArgumentMatchers.argThat(command -> command != null && Objects.equals(command.tenantId(), "ta") && Objects.equals(command.calendarCode(), "default-calendar") && command.bizDate() != null && Objects.equals(command.action(), "FREEZE") && Objects.equals(command.operatorId(), "ops-user") && Objects.equals(command.reason(), "safety"))))
        .thenReturn(new ConsoleBatchDayOperateResponse(1L, "FROZEN", true, 0));
    mockMvc
        .perform(post("/api/console/batch-days/operate")
            .contentType(APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "ta",
                  "calendarCode": "default-calendar",
                  "bizDate": "2026-05-20",
                  "action": "FREEZE",
                  "operatorId": "admin",
                  "reason": "safety"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.batchDayId").value(1))
        .andExpect(jsonPath("$.data.dayStatus").value("FROZEN"));
    verify(proxy)
        .batchDayOperate(new BatchDayOperationCommand("ta", "default-calendar", LocalDate.parse("2026-05-20"), "FREEZE", "ops-user", "safety"));
  }

  @Test
  @DisplayName("空日确认操作可透传且忽略客户端伪造的审计操作者")
  void shouldUseAuthenticatedOperatorForEmptyDaySettlement() throws Exception {
    when(proxy.batchDayOperate(org.mockito.ArgumentMatchers.argThat(command -> command != null && Objects.equals(command.tenantId(), "ta") && Objects.equals(command.calendarCode(), "CAL") && Objects.equals(command.bizDate(), LocalDate.parse("2026-05-20")) && Objects.equals(command.action(), "SETTLE_EMPTY") && Objects.equals(command.operatorId(), "ops-user") && Objects.equals(command.reason(), "confirmed no expected jobs"))))
        .thenReturn(new ConsoleBatchDayOperateResponse(4L, "SETTLED", false, 0));

    mockMvc
        .perform(post("/api/console/batch-days/operate")
            .contentType(APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "ta",
                  "calendarCode": "CAL",
                  "bizDate": "2026-05-20",
                  "action": "SETTLE_EMPTY",
                  "operatorId": "forged-client-user",
                  "reason": "confirmed no expected jobs"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.dayStatus").value("SETTLED"));

    verify(proxy)
        .batchDayOperate(new BatchDayOperationCommand("ta", "CAL", LocalDate.parse("2026-05-20"), "SETTLE_EMPTY", "ops-user", "confirmed no expected jobs"));
  }

  @Test
  @DisplayName("只读 catch-up 预览按当前租户和日历转发到 Orchestrator")
  void shouldForwardCatchupPreviewQuery() throws Exception {
    when(proxy.previewBatchDayCatchup("ta", "CAL"))
        .thenReturn(new ConsoleBatchDayCatchupPreviewResponse(
            "ta",
            "CAL",
            LocalDate.of(2026, 5, 5),
            LocalDate.of(2026, 5, 3),
            "PERSISTED_CURSOR",
            LocalDate.of(2026, 5, 4),
            LocalDate.of(2026, 5, 5),
            2,
            14,
            List.of(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 5)),
            false,
            false,
            null,
            null,
            null));

    mockMvc
        .perform(get("/api/console/batch-days/catch-up-preview")
            .param("tenantId", "ta")
            .param("calendarCode", "CAL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.remainingRawDateCount").value(2))
        .andExpect(jsonPath("$.data.nextScanRawBizDates.length()").value(2));

    verify(proxy).previewBatchDayCatchup("ta", "CAL");
  }

  @Test
  @DisplayName("历史缺口候选查询将租户、日历和有界日期范围转发到 Orchestrator")
  void shouldForwardHistoricalGapCandidateQuery() throws Exception {
    when(proxy.inventoryBatchDayHistoricalGaps(
            "ta", "CAL", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 4)))
        .thenReturn(new ConsoleBatchDayHistoricalGapInventoryResponse(
            "ta",
            "CAL",
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 4),
            4,
            List.of(LocalDate.of(2026, 4, 2)),
            "Missing records are candidates only."));

    mockMvc
        .perform(get("/api/console/batch-days/historical-gap-candidates")
            .param("tenantId", "ta")
            .param("calendarCode", "CAL")
            .param("fromDate", "2026-04-01")
            .param("throughDate", "2026-04-04"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.inspectedDateCount").value(4))
        .andExpect(jsonPath("$.data.missingRecordCandidates[0]").value("2026-04-02"));

    verify(proxy)
        .inventoryBatchDayHistoricalGaps(
            "ta", "CAL", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 4));
  }

  @Test
  @DisplayName("catch-up 暂停请求使用认证上下文操作者并要求填写原因")
  void shouldUseAuthenticatedOperatorForCatchupControl() throws Exception {
    when(proxy.changeBatchDayCatchupControl("ta", "CAL", "PAUSE", "ops-user", "maintenance"))
        .thenReturn(new ConsoleBatchDayCatchupPreviewResponse(
            "ta",
            "CAL",
            LocalDate.of(2026, 5, 5),
            LocalDate.of(2026, 5, 3),
            "PERSISTED_CURSOR",
            LocalDate.of(2026, 5, 4),
            LocalDate.of(2026, 5, 5),
            2,
            14,
            List.of(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 5)),
            false,
            true,
            BatchDateTimeSupport.utcNow(),
            "ops-user",
            "maintenance"));

    mockMvc
        .perform(post("/api/console/batch-days/catch-up-control")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"ta","calendarCode":"CAL","action":"PAUSE","reason":"maintenance"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.catchupPaused").value(true))
        .andExpect(jsonPath("$.data.catchupPausedBy").value("ops-user"));

    verify(proxy).changeBatchDayCatchupControl("ta", "CAL", "PAUSE", "ops-user", "maintenance");
  }

  @Test
  @DisplayName("动作取值不在允许集合内时返回参数错误, 不触达下游端口")
  void shouldRejectUnsupportedAction_whenActionNotInAllowedSet() throws Exception {
    // @Pattern 限制 action 必须是 FREEZE/RELEASE/SKIP/REOPEN/CLOSE 之一
    mockMvc
        .perform(post("/api/console/batch-days/operate")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"calendarCode\":\"c1\",\"bizDate\":\"2026-05-20\","
                + "\"action\":\"BAD_ACTION\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("缺少日历编码时返回参数错误, 不触达下游端口")
  void shouldRejectMissingCalendarCode_whenCalendarCodeBlank() throws Exception {
    mockMvc
        .perform(post("/api/console/batch-days/operate")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"bizDate\":\"2026-05-20\",\"action\":\"FREEZE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("五个受支持动作逐一提交均返回成功")
  void shouldAcceptAllSupportedActions_whenActionValid() throws Exception {
    when(proxy.batchDayOperate(org.mockito.ArgumentMatchers.any(BatchDayOperationCommand.class)))
        .thenReturn(new ConsoleBatchDayOperateResponse(null, null, null, null));
    for (String act : new String[] {"FREEZE", "RELEASE", "SKIP", "REOPEN", "CLOSE"}) {
      mockMvc
          .perform(post("/api/console/batch-days/operate")
              .contentType(APPLICATION_JSON)
              .content("""
                {
                  "tenantId": "ta",
                  "calendarCode": "c1",
                  "bizDate": "2026-05-20",
                  "action": "%s"
                }
                """.formatted(act)))
          .andExpect(status().isOk());
    }
  }
}
