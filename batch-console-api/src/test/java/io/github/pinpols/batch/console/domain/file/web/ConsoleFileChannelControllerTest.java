package io.github.pinpols.batch.console.domain.file.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileChannelApplicationService;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileProjectionMapper;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("文件通道控制器: 查询、创建、启停与更新的参数校验")
@ExtendWith(MockitoExtension.class)
class ConsoleFileChannelControllerTest {

  @Mock
  private ConsoleFileChannelApplicationService applicationService;

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
            new ConsoleFileChannelController(applicationService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("按标识查询通道返回成功与租户信息")
  void shouldReturn200WhenGetChannelById() throws Exception {
    when(applicationService.get(anyLong(), anyString()))
        .thenReturn(ConsoleFileProjectionMapper.channelView(
            FileChannelConfigQueryView.builder().id(1L).tenantId("t1").build()));

    mockMvc
        .perform(get("/api/console/file-channels/1").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenantId").value("t1"));
  }

  @Test
  @DisplayName("创建通道缺少必填字段时返回校验错误")
  void shouldReturn400WhenCreateRequestMissingRequired() throws Exception {
    mockMvc
        .perform(
            post("/api/console/file-channels").contentType(APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    verifyNoInteractions(applicationService);
  }

  @Test
  @DisplayName("启停通道返回成功")
  void shouldReturn200WhenToggleChannel() throws Exception {
    mockMvc
        .perform(patch("/api/console/file-channels/1")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }

  @Test
  @DisplayName("启停通道缺少租户标识时返回校验错误")
  void shouldReturn400WhenToggleChannelMissingTenantId() throws Exception {
    mockMvc
        .perform(patch("/api/console/file-channels/1")
            .contentType(APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    verifyNoInteractions(applicationService);
  }

  @Test
  @DisplayName("更新通道返回成功")
  void shouldReturn200WhenUpdateChannel() throws Exception {
    when(applicationService.update(anyLong(), any()))
        .thenReturn(ConsoleFileProjectionMapper.channelView(
            FileChannelConfigQueryView.builder().id(1L).tenantId("t1").build()));

    mockMvc
        .perform(put("/api/console/file-channels/1")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }
}
