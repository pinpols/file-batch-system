package io.github.pinpols.batch.console.domain.rbac.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.contract.response.config.QuotaPolicyResponse;
import io.github.pinpols.batch.console.domain.rbac.application.ConsoleTenantSelfServiceApplicationService;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleTenantUsageSummaryResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("租户自助控制器: 配额查询与用量汇总接口")
@ExtendWith(MockitoExtension.class)
class ConsoleTenantSelfServiceControllerTest {

  @Mock
  private ConsoleTenantSelfServiceApplicationService applicationService;

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

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleTenantSelfServiceController(
            applicationService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("按租户返回配额策略分页列表")
  void shouldReturnQuota() throws Exception {
    QuotaPolicyResponse policy =
        new QuotaPolicyResponse(1L, "t1", "max-jobs", 100, 0, 0, 1, true, null, null, null);
    when(applicationService.quota("t1"))
        .thenReturn(new PageResponse<>(1, 1, 100, List.of(policy)));

    mockMvc
        .perform(get("/api/console/tenants/quota").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].policyCode").value("max-jobs"));
  }

  @Test
  @DisplayName("按租户汇总运行作业、日触发与文件数量")
  void shouldReturnUsage() throws Exception {
    when(applicationService.usage("t1"))
        .thenReturn(new ConsoleTenantUsageSummaryResponse("5", "120", "42"));

    mockMvc
        .perform(get("/api/console/tenants/usage").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.runningJobs").value("5"))
        .andExpect(jsonPath("$.data.dailyTriggers").value("120"))
        .andExpect(jsonPath("$.data.fileCount").value("42"));
  }
}
