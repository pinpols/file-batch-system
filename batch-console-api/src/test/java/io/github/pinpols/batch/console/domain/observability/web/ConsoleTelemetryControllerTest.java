package io.github.pinpols.batch.console.domain.observability.web;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleTelemetryService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleTelemetryController 接收前端事件 + 大小/字段约束。 */
@DisplayName("遥测事件接口:事件上报与请求体约束校验")
@ExtendWith(MockitoExtension.class)
class ConsoleTelemetryControllerTest {

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
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleTelemetryController(responseFactory, new ConsoleTelemetryService()))
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
                    "{\"app\":\"console\",\"events\":[{\"type\":\"route\",\"name\":\"page_view\",\"page\":\"/home\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }

  @Test
  @DisplayName("遥测日志不写入调用方提供的事件名称和页面内容")
  void shouldNotLogCallerControlledTelemetryText() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(ConsoleTelemetryController.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      mockMvc
          .perform(
              post("/api/console/telemetry/events")
                  .contentType(APPLICATION_JSON)
                  .content(
                      "{\"app\":\"console\",\"events\":[{\"type\":\"error\",\"name\":\"secret-value\",\"page\":\"/ops?token=page-secret\"}]}"))
          .andExpect(status().isOk());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    String logged = appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .reduce("", (left, right) -> left + right);
    Assertions.assertThat(logged)
        .contains("nameLength=12", "pagePresent=true")
        .doesNotContain("secret-value", "page-secret", "token=");
  }

  @Test
  @DisplayName("遥测上报:兼容有界的扩展事件类型")
  void shouldAccept_whenEventTypeIsBoundedExtension() throws Exception {
    mockMvc
        .perform(post("/api/console/telemetry/events")
            .contentType(APPLICATION_JSON)
            .content(
                "{\"app\":\"console\",\"events\":[{\"type\":\"custom\",\"name\":\"page_view\"}]}"))
        .andExpect(status().isOk());
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"app\":\"console\",\"events\":[null]}",
        "{\"app\":\"console\",\"events\":[{\"type\":\"route\",\"name\":\"view\"},null]}"
      })
  @DisplayName("遥测事件数组包含空元素时返回参数错误，不能进入控制器空指针路径")
  void shouldRejectNullEvent_whenListContainsNull(String payload) throws Exception {
    mockMvc
        .perform(
            post("/api/console/telemetry/events").contentType(APPLICATION_JSON).content(payload))
        .andExpect(status().isBadRequest());
  }
}
