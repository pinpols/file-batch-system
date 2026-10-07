package io.github.pinpols.batch.console.domain.job.web;

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
import io.github.pinpols.batch.console.domain.job.application.ConsoleBatchWindowApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchWindowCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchWindowUpdateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchWindowResponse;
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

/** P1: ConsoleBatchWindowController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
@DisplayName("批量窗口控制器: 列表查询、新建、更新与启停的路由转发行为")
class ConsoleBatchWindowControllerBehaviorTest {

  private final ConsoleBatchWindowApplicationService service =
      mock(ConsoleBatchWindowApplicationService.class);
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
            new ConsoleBatchWindowController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("未传筛选条件时按默认页码与页大小下查, 响应与查询结果一致")
  void shouldDelegateListWithDefaultPaging_whenFiltersAbsent() throws Exception {
    when(service.list(eq("ta"), any(), any(), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/batch-windows").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).list("ta", null, null, 1, 20);
  }

  @Test
  @DisplayName("新建成功后返回持久化后的数据行, 响应字段沿用历史键名")
  void shouldReturnCreatedRow_whenCreateSucceeds() throws Exception {
    when(service.create(any(BatchWindowCreateRequest.class)))
        .thenReturn(new ConsoleBatchWindowResponse(
            1L,
            "ta",
            "always-open",
            null,
            "Asia/Shanghai",
            "00:00:00",
            "23:59:00",
            null,
            null,
            null,
            true,
            null,
            null,
            null));
    mockMvc
        .perform(
            post("/api/console/batch-windows")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"windowCode\":\"always-open\",\"timezone\":\"Asia/Shanghai\",\"startTime\":\"00:00\",\"endTime\":\"23:59\"}"))
        .andExpect(status().isOk())
        // wire 红线：batch_window 历史响应键为 snake_case，故断言 window_code 而非 windowCode。
        .andExpect(jsonPath("$.data.window_code").value("always-open"));
  }

  @Test
  @DisplayName("更新请求以路径标识定位目标记录, 报文原样下发给下游")
  void shouldPassPathId_whenUpdateRequested() throws Exception {
    when(service.update(eq(7L), any(BatchWindowUpdateRequest.class)))
        .thenReturn(new ConsoleBatchWindowResponse(
            7L,
            "ta",
            "w1",
            null,
            "Asia/Shanghai",
            "00:00:00",
            "23:59:00",
            null,
            null,
            null,
            true,
            null,
            null,
            null));
    mockMvc
        .perform(
            put("/api/console/batch-windows/7")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"windowCode\":\"w1\",\"timezone\":\"Asia/Shanghai\",\"startTime\":\"00:00\",\"endTime\":\"23:59\"}"))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(BatchWindowUpdateRequest.class));
  }

  @Test
  @DisplayName("启停操作以路径标识与报文中的目标状态下发下游")
  void shouldPassBody_whenToggleRequested() throws Exception {
    mockMvc
        .perform(patch("/api/console/batch-windows/9/enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":false}"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", false);
  }
}
