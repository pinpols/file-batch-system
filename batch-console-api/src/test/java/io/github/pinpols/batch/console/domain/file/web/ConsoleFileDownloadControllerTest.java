package io.github.pinpols.batch.console.domain.file.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.console.domain.file.application.ConsoleFileDownloadApplicationService;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("文件下载控制器: 流式响应与租户参数校验")
class ConsoleFileDownloadControllerTest {

  @Mock
  private ConsoleFileDownloadApplicationService applicationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleFileDownloadController(applicationService))
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("下载成功时以二进制流返回并附带内容类型")
  void shouldReturn200WithStreamWhenDownloadSucceeds() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("test.csv").build());

    when(applicationService.download(anyString(), anyLong(), any()))
        .thenReturn(ResponseEntity.ok()
            .headers(headers)
            .body(new InputStreamResource(new ByteArrayInputStream("data".getBytes()))));

    mockMvc
        .perform(get("/api/console/files/1/download").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE));
  }

  @Test
  @DisplayName("缺少租户参数时返回 400")
  void shouldReturn400WhenTenantIdMissing() throws Exception {
    mockMvc.perform(get("/api/console/files/1/download")).andExpect(status().isBadRequest());
  }
}
