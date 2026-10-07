package io.github.pinpols.batch.console.domain.observability.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleTelemetryController 接收前端事件 + 大小/字段约束。 */
@DisplayName("遥测事件接口:事件上报与请求体约束校验")
class ConsoleTelemetryControllerTest {

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
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleTelemetryController(responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("遥测上报:合法事件载荷返回成功")
  void shouldAcceptEvents_whenPayloadValid() throws Exception {
    mockMvc
        .perform(
            post("/api/console/telemetry/events")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"app\":\"console\",\"events\":[{\"type\":\"info\",\"name\":\"page_view\",\"page\":\"/home\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }

  @Test
  @DisplayName("遥测上报:事件列表为空时返回参数非法")
  void shouldReject_whenEventsListEmpty() throws Exception {
    mockMvc
        .perform(post("/api/console/telemetry/events")
            .contentType(APPLICATION_JSON)
            .content("{\"app\":\"console\",\"events\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("遥测上报:缺少应用标识字段时返回参数非法")
  void shouldReject_whenAppFieldMissing() throws Exception {
    mockMvc
        .perform(post("/api/console/telemetry/events")
            .contentType(APPLICATION_JSON)
            .content("{\"events\":[{\"type\":\"info\",\"name\":\"x\"}]}"))
        .andExpect(status().isBadRequest());
  }
}
