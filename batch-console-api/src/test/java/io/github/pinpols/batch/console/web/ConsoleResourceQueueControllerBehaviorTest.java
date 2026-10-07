package io.github.pinpols.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleResourceQueueApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("资源队列控制器行为: 启停动作的入参委托")
class ConsoleResourceQueueControllerBehaviorTest {

  private final ConsoleResourceQueueApplicationService service =
      mock(ConsoleResourceQueueApplicationService.class);
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
            new ConsoleResourceQueueController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("启停资源队列时, 队列标识、租户与目标状态传给服务")
  void shouldDelegateToggle_whenSettingQueueEnabled() throws Exception {
    mockMvc
        .perform(patch("/api/console/queues/9/enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":true}"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", true);
  }
}
