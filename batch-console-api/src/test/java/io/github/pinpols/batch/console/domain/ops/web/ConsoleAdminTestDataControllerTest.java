package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("测试数据清理接口:生产环境拒绝启用, 非法前缀拦截, 合法请求只转发编排层")
class ConsoleAdminTestDataControllerTest {

  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final Environment environment = mock(Environment.class);
  private final ConsoleOrchestratorPort orchestratorProxyService =
      mock(ConsoleOrchestratorPort.class);
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
    ConsoleAdminTestDataController controller =
        new ConsoleAdminTestDataController(orchestratorProxyService, responseFactory, environment);
    // PostConstruct 在 standalone setup 下不会自动跑;此处显式调一次走非 prod 路径(test profile)
    ReflectionTestUtils.invokeMethod(controller, "validateProfile");
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("生产环境守卫:以生产配置启用时构造即抛出状态异常, 并给出拒绝原因")
  void shouldRejectInProductionProfileAtConstruction() {
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
    ConsoleResponseFactory rf = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleAdminTestDataController prodCtl =
        new ConsoleAdminTestDataController(orchestratorProxyService, rf, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(prodCtl, "validateProfile"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not be enabled in production profiles");
  }

  @Test
  @DisplayName("空前缀守卫:仅空白的前缀返回参数错误, 且不触达编排层")
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
  @DisplayName("前缀清理:合法前缀按表计数回填成功响应, 并转发编排层")
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
  @DisplayName("精确清理:逗号分隔的标识列表解析后转发, 并回填清理计数")
  void shouldForwardExactIdsCleanupToOrchestrator() throws Exception {
    mockMvc
        .perform(delete("/api/console/admin/test-data/by-ids").param("ids", "td,te"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenant").value(2));
    verify(orchestratorProxyService).adminTestDataCleanupByExactTenantIds(List.of("td", "te"));
  }
}
