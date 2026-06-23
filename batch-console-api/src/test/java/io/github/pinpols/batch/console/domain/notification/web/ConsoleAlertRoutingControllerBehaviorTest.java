package io.github.pinpols.batch.console.domain.notification.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertRoutingApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.request.config.AlertRoutingSaveRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P1: ConsoleAlertRoutingController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
class ConsoleAlertRoutingControllerBehaviorTest {

  private final ConsoleAlertRoutingApplicationService service =
      mock(ConsoleAlertRoutingApplicationService.class);
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
        MockMvcBuilders.standaloneSetup(new ConsoleAlertRoutingController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private String saveBody(String routeCode) {
    return "{"
        + "\"tenantId\":\"ta\","
        + "\"routeCode\":\""
        + routeCode
        + "\","
        + "\"team\":\"ops\","
        + "\"alertGroup\":\"default\","
        + "\"severity\":\"WARN\","
        + "\"receiver\":\"ops@example.com\""
        + "}";
  }

  @Test
  void listShouldPassAllFilters() throws Exception {
    when(service.list(eq("ta"), eq("RT_A"), eq("ops"), eq("WARN"), eq(true), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(
            get("/api/console/alert-routings")
                .param("tenantId", "ta")
                .param("routeCode", "RT_A")
                .param("team", "ops")
                .param("severity", "WARN")
                .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).list("ta", "RT_A", "ops", "WARN", true, 1, 20);
  }

  @Test
  void createShouldReturnRow() throws Exception {
    when(service.create(any(AlertRoutingSaveRequest.class)))
        .thenReturn(Map.of("id", 1L, "routeCode", "RT_NEW"));
    mockMvc
        .perform(
            post("/api/console/alert-routings")
                .contentType(APPLICATION_JSON)
                .content(saveBody("RT_NEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.routeCode").value("RT_NEW"));
  }

  @Test
  void updateShouldPassPathId() throws Exception {
    when(service.update(eq(7L), any(AlertRoutingSaveRequest.class))).thenReturn(Map.of("id", 7L));
    mockMvc
        .perform(
            put("/api/console/alert-routings/7")
                .contentType(APPLICATION_JSON)
                .content(saveBody("RT_U")))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(AlertRoutingSaveRequest.class));
  }

  @Test
  void toggleShouldDelegate() throws Exception {
    mockMvc
        .perform(
            post("/api/console/alert-routings/9/toggle")
                .param("tenantId", "ta")
                .param("enabled", "false"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", false);
  }
}
