package io.github.pinpols.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.QuotaPolicySaveRequest;
import io.github.pinpols.batch.console.application.contract.response.config.QuotaPolicyResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P1: ConsoleQuotaPolicyController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
@DisplayName("配额策略控制器行为: 列表筛选、新建、更新与启停的委托与响应")
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
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleQuotaPolicyController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  private String saveBody(String policyCode) {
    return """
        {
          "tenantId": "ta",
          "policyCode": "%s",
          "maxRunningJobsPerTenant": 10,
          "maxQpsPerTenant": 100
        }
        """.formatted(policyCode).stripTrailing();
  }

  @Test
  @DisplayName("按租户与策略编码查询配额策略时, 筛选条件原样传给服务")
  void shouldPassFilters_whenListingQuotaPolicies() throws Exception {
    when(service.list("ta", "QP_A", true, 1, 20))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/quota-policies")
            .param("tenantId", "ta")
            .param("policyCode", "QP_A")
            .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).list("ta", "QP_A", true, 1, 20);
  }

  @Test
  @DisplayName("新建配额策略时, 响应返回新建的策略编码")
  void shouldReturnCreatedRow_whenCreatingQuotaPolicy() throws Exception {
    when(service.create(any(QuotaPolicySaveRequest.class))).thenReturn(policy(1L, "QP_NEW"));
    mockMvc
        .perform(post("/api/console/quota-policies")
            .contentType(APPLICATION_JSON)
            .content(saveBody("QP_NEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.policyCode").value("QP_NEW"));
  }

  @Test
  @DisplayName("更新配额策略时, 路径标识作为入参传给服务")
  void shouldPassPathId_whenUpdatingQuotaPolicy() throws Exception {
    when(service.update(eq(7L), any(QuotaPolicySaveRequest.class))).thenReturn(policy(7L, "QP_U"));
    mockMvc
        .perform(put("/api/console/quota-policies/7")
            .contentType(APPLICATION_JSON)
            .content(saveBody("QP_U")))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(QuotaPolicySaveRequest.class));
  }

  @Test
  @DisplayName("启停配额策略时, 策略标识、租户与目标状态传给服务")
  void shouldDelegateToggle_whenSettingQuotaPolicyEnabled() throws Exception {
    mockMvc
        .perform(patch("/api/console/quota-policies/9/enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":true}"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", true);
  }

  private static QuotaPolicyResponse policy(Long id, String policyCode) {
    return new QuotaPolicyResponse(id, "ta", policyCode, 10, 0, 100, 1, true, null, null, null);
  }
}
