package io.github.pinpols.batch.console.web;

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
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.request.config.QuotaPolicySaveRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P1: ConsoleQuotaPolicyController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
class ConsoleQuotaPolicyControllerBehaviorTest {

  private final ConsoleQuotaPolicyApplicationService service =
      mock(ConsoleQuotaPolicyApplicationService.class);
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
        MockMvcBuilders.standaloneSetup(new ConsoleQuotaPolicyController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private String saveBody(String policyCode) {
    return "{"
        + "\"tenantId\":\"ta\","
        + "\"policyCode\":\""
        + policyCode
        + "\","
        + "\"maxRunningJobsPerTenant\":10,"
        + "\"maxQpsPerTenant\":100"
        + "}";
  }

  @Test
  void listShouldPassFilters() throws Exception {
    when(service.list(eq("ta"), eq("QP_A"), eq(true), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(
            get("/api/console/quota-policies")
                .param("tenantId", "ta")
                .param("policyCode", "QP_A")
                .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).list("ta", "QP_A", true, 1, 20);
  }

  @Test
  void createShouldReturnRow() throws Exception {
    when(service.create(any(QuotaPolicySaveRequest.class)))
        .thenReturn(Map.of("id", 1L, "policyCode", "QP_NEW"));
    mockMvc
        .perform(
            post("/api/console/quota-policies")
                .contentType(APPLICATION_JSON)
                .content(saveBody("QP_NEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.policyCode").value("QP_NEW"));
  }

  @Test
  void updateShouldPassPathId() throws Exception {
    when(service.update(eq(7L), any(QuotaPolicySaveRequest.class))).thenReturn(Map.of("id", 7L));
    mockMvc
        .perform(
            put("/api/console/quota-policies/7")
                .contentType(APPLICATION_JSON)
                .content(saveBody("QP_U")))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(QuotaPolicySaveRequest.class));
  }

  @Test
  void toggleShouldDelegate() throws Exception {
    mockMvc
        .perform(
            post("/api/console/quota-policies/9/toggle")
                .param("tenantId", "ta")
                .param("enabled", "false"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", false);
  }
}
