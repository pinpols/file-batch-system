package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleBatchDayController operate 转发到 orchestrator + action 枚举校验。 */
class ConsoleBatchDayControllerTest {

  private final ConsoleOrchestratorProxyService proxy = mock(ConsoleOrchestratorProxyService.class);
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
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleBatchDayController(proxy, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void operateShouldForwardAllFieldsToProxy() throws Exception {
    when(proxy.batchDayOperate(
            eq("ta"),
            eq("default-calendar"),
            any(LocalDate.class),
            eq("FREEZE"),
            eq("admin"),
            eq("safety")))
        .thenReturn(Map.of("batchDayId", 1, "dayStatus", "FROZEN"));
    mockMvc
        .perform(
            post("/api/console/batch-days/operate")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"calendarCode\":\"default-calendar\","
                        + "\"bizDate\":\"2026-05-20\",\"action\":\"FREEZE\","
                        + "\"operatorId\":\"admin\",\"reason\":\"safety\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.batchDayId").value(1))
        .andExpect(jsonPath("$.data.dayStatus").value("FROZEN"));
    verify(proxy)
        .batchDayOperate(
            "ta", "default-calendar", LocalDate.parse("2026-05-20"), "FREEZE", "admin", "safety");
  }

  @Test
  void operateShouldRejectUnsupportedAction() throws Exception {
    // @Pattern 限制 action 必须是 FREEZE/RELEASE/SKIP/REOPEN/CLOSE 之一
    mockMvc
        .perform(
            post("/api/console/batch-days/operate")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"calendarCode\":\"c1\",\"bizDate\":\"2026-05-20\","
                        + "\"action\":\"BAD_ACTION\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void operateShouldRejectMissingCalendarCode() throws Exception {
    mockMvc
        .perform(
            post("/api/console/batch-days/operate")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\",\"bizDate\":\"2026-05-20\",\"action\":\"FREEZE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void operateShouldAcceptAllFiveValidActions() throws Exception {
    when(proxy.batchDayOperate(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
    for (String act : new String[] {"FREEZE", "RELEASE", "SKIP", "REOPEN", "CLOSE"}) {
      mockMvc
          .perform(
              post("/api/console/batch-days/operate")
                  .contentType(APPLICATION_JSON)
                  .content(
                      "{\"tenantId\":\"ta\",\"calendarCode\":\"c1\","
                          + "\"bizDate\":\"2026-05-20\",\"action\":\""
                          + act
                          + "\"}"))
          .andExpect(status().isOk());
    }
  }
}
