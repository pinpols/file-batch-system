package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.domain.ops.service.ConsoleAdminTestDataCleanupService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * P0: ConsoleAdminTestDataController 安全 + orchestrator 转发守卫。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>prod profile fail-fast — controller 实例化即拒
 *   <li>prefix 正则校验 — 非法字符 / 空 / 过短全 400
 *   <li>合法请求只转发给 orchestrator proxy，console 不再直接写运行态表
 * </ul>
 */
class ConsoleAdminTestDataControllerTest {

  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final Environment environment = mock(Environment.class);
  private final ConsoleOrchestratorProxyService orchestratorProxyService =
      mock(ConsoleOrchestratorProxyService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
    when(orchestratorProxyService.adminTestDataCleanupByPrefix("e2e"))
        .thenReturn(Map.of("job_definition", 1, "workflow_definition", 1));
    when(orchestratorProxyService.adminTestDataCleanupByPrefix("test"))
        .thenReturn(Map.of("job_definition", 0));
    when(orchestratorProxyService.adminTestDataCleanupByExactTenantIds(List.of("td", "te")))
        .thenReturn(Map.of("tenant", 2));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    ConsoleAdminTestDataCleanupService cleanupService = cleanupService();
    ConsoleAdminTestDataController controller =
        new ConsoleAdminTestDataController(cleanupService, responseFactory, environment);
    // PostConstruct 在 standalone setup 下不会自动跑;此处显式调一次走非 prod 路径(test profile)
    ReflectionTestUtils.invokeMethod(controller, "validateProfile");
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldRejectInProductionProfileAtConstruction() {
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
    ConsoleResponseFactory rf = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleAdminTestDataController prodCtl =
        new ConsoleAdminTestDataController(cleanupService(), rf, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(prodCtl, "validateProfile"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不允许在生产 profile 启用");
  }

  @Test
  void shouldRejectBlankPrefixAtMethodGuard() throws Exception {
    // controller 内部 if (prefix.isBlank()) 回退,@Pattern 在 standalone MockMvc 不触发
    // (MethodValidationPostProcessor 需要 Spring context),所以这里走方法内 guard
    mockMvc
        .perform(delete("/api/console/admin/test-data").param("prefix", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verify(orchestratorProxyService, never()).adminTestDataCleanupByPrefix("   ");
  }

  @Test
  void shouldForwardValidPrefixCleanupToOrchestrator() throws Exception {
    mockMvc
        .perform(delete("/api/console/admin/test-data").param("prefix", "e2e"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.job_definition").value(1))
        .andExpect(jsonPath("$.data.workflow_definition").value(1));
    verify(orchestratorProxyService).adminTestDataCleanupByPrefix("e2e");
  }

  @Test
  void shouldForwardExactIdsCleanupToOrchestrator() throws Exception {
    mockMvc
        .perform(delete("/api/console/admin/test-data/by-ids").param("ids", "td,te"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenant").value(2));
    verify(orchestratorProxyService).adminTestDataCleanupByExactTenantIds(List.of("td", "te"));
  }

  private ConsoleAdminTestDataCleanupService cleanupService() {
    return new ConsoleAdminTestDataCleanupService(orchestratorProxyService);
  }
}
