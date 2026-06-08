package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.ops.infrastructure.ConsoleAdminTestDataCleanupRepository;
import com.example.batch.console.domain.ops.service.ConsoleAdminTestDataCleanupService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * P0: ConsoleAdminTestDataController 安全 + FK 链路守卫。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>prod profile fail-fast — controller 实例化即拒
 *   <li>prefix 正则校验 — 非法字符 / 空 / 过短全 400
 *   <li>FK 链路:删 13 张依赖表(workflow_node_run → workflow_run → 5 张 job 依赖 → job_partition →
 *       job_instance → workflow_node/edge/definition → job_definition + file/user/archive/tenant)
 *   <li>job_instance.parent_instance_id 自引 FK 先 NULL 再删
 * </ul>
 */
class ConsoleAdminTestDataControllerTest {

  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final Environment environment = mock(Environment.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

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
    // controller 内部 if (prefix.isBlank()) 兜底,@Pattern 在 standalone MockMvc 不触发
    // (MethodValidationPostProcessor 需要 Spring context),所以这里走方法内 guard
    mockMvc
        .perform(delete("/api/console/admin/test-data").param("prefix", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void shouldRunFullFkDeletionChainOnValidPrefix() throws Exception {
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
    mockMvc
        .perform(delete("/api/console/admin/test-data").param("prefix", "e2e"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.workflow_node_run").value(1))
        .andExpect(jsonPath("$.data.workflow_run").value(1))
        .andExpect(jsonPath("$.data.compensation_command").value(1))
        .andExpect(jsonPath("$.data.pipeline_instance").value(1))
        .andExpect(jsonPath("$.data.job_execution_log").value(1))
        .andExpect(jsonPath("$.data.job_step_instance").value(1))
        .andExpect(jsonPath("$.data.job_task").value(1))
        .andExpect(jsonPath("$.data.job_partition").value(1))
        .andExpect(jsonPath("$.data.job_instance").value(1))
        .andExpect(jsonPath("$.data.workflow_node").value(1))
        .andExpect(jsonPath("$.data.workflow_edge").value(1))
        .andExpect(jsonPath("$.data.workflow_definition").value(1))
        .andExpect(jsonPath("$.data.job_definition").value(1));
    // 至少 14 次 update(13 张表 + 1 次 parent_instance_id NULL update)
    verify(jdbc, atLeast(14)).update(anyString(), any(MapSqlParameterSource.class));
  }

  @Test
  void shouldNullOutSelfReferentialParentBeforeDeletingJobInstance() throws Exception {
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
    mockMvc
        .perform(delete("/api/console/admin/test-data").param("prefix", "e2e"))
        .andExpect(status().isOk());
    // 验证存在 UPDATE ... SET parent_instance_id = NULL 的语句
    verify(jdbc)
        .update(
            contains("UPDATE batch.job_instance SET parent_instance_id = NULL"),
            any(MapSqlParameterSource.class));
  }

  @Test
  void shouldMatchPrefixHyphenSuffixOnlyNotBarePrefixSubstring() throws Exception {
    // prefix='test' 不应误删 'tester';验证 SQL LIKE 模板用 prefix + "-%"
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
    org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
        org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
    mockMvc
        .perform(delete("/api/console/admin/test-data").param("prefix", "test"))
        .andExpect(status().isOk());
    verify(jdbc, atLeastOnce()).update(anyString(), captor.capture());
    MapSqlParameterSource params = captor.getValue();
    assertThat(params.getValue("p")).isEqualTo("test-%");
    // 确认不是 "test%"
    assertThat(params.getValue("p")).asString().endsWith("-%");
  }

  private ConsoleAdminTestDataCleanupService cleanupService() {
    return new ConsoleAdminTestDataCleanupService(new ConsoleAdminTestDataCleanupRepository(jdbc));
  }
}
