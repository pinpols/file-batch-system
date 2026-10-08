package io.github.pinpols.batch.console.domain.notification.web;

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
import io.github.pinpols.batch.console.application.contract.request.config.AlertRoutingSaveRequest;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertRoutingApplicationService;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertRoutingResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P1: ConsoleAlertRoutingController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
@DisplayName("告警路由接口: 条件查询, 新增改与启停的入参透传")
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
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleAlertRoutingController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  private String saveBody(String routeCode) {
    return """
        {
          "tenantId": "ta",
          "routeCode": "%s",
          "team": "ops",
          "alertGroup": "default",
          "severity": "WARN",
          "receiver": "ops@example.com"
        }
        """.formatted(routeCode).stripTrailing();
  }

  @Test
  @DisplayName("清单查询把租户, 路由编码, 团队, 级别与启用状态全部透传给服务")
  void shouldPassAllFilters_whenListingRoutings() throws Exception {
    when(service.list("ta", "RT_A", "ops", "WARN", true, 1, 20))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/alert-routings")
            .param("tenantId", "ta")
            .param("routeCode", "RT_A")
            .param("team", "ops")
            .param("severity", "WARN")
            .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).list("ta", "RT_A", "ops", "WARN", true, 1, 20);
  }

  @Test
  @DisplayName("新增路由返回该行, 响应字段保持下划线命名")
  void shouldReturnRow_whenCreatingRouting() throws Exception {
    // 生产 AlertRoutingConfigMapper 以 resultType=map 返回 snake_case 列键（route_code），
    // 类型化响应经 @JsonProperty 保持 snake_case wire 一字不差。
    when(service.create(any(AlertRoutingSaveRequest.class)))
        .thenReturn(ConsoleAlertRoutingResponse.from(Map.of("id", 1L, "route_code", "RT_NEW")));
    mockMvc
        .perform(post("/api/console/alert-routings")
            .contentType(APPLICATION_JSON)
            .content(saveBody("RT_NEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.route_code").value("RT_NEW"));
  }

  @Test
  @DisplayName("更新路由以路径主键为准, 请求体随附提交")
  void shouldPassPathId_whenUpdatingRouting() throws Exception {
    when(service.update(eq(7L), any(AlertRoutingSaveRequest.class)))
        .thenReturn(ConsoleAlertRoutingResponse.from(Map.of("id", 7L)));
    mockMvc
        .perform(put("/api/console/alert-routings/7")
            .contentType(APPLICATION_JSON)
            .content(saveBody("RT_U")))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(AlertRoutingSaveRequest.class));
  }

  @Test
  @DisplayName("启停路由时, 按路径主键与租户委托服务处理")
  void shouldDelegate_whenTogglingEnabled() throws Exception {
    mockMvc
        .perform(patch("/api/console/alert-routings/9/enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":true}"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", true);
  }
}
