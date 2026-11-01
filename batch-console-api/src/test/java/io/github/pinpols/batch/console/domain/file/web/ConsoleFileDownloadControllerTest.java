package io.github.pinpols.batch.console.domain.file.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.console.domain.file.application.ConsoleFileDownloadApplicationService;
import io.github.pinpols.batch.console.domain.file.application.FileDownloadArtifact;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
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
    when(applicationService.download(anyString(), anyLong(), any()))
        .thenReturn(new FileDownloadArtifact(
            new ByteArrayInputStream("data".getBytes()), "test.csv", "application/octet-stream", true));

    mockMvc
        .perform(get("/api/console/files/1/download").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"test.csv\""))
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE));
  }

  @Test
  @DisplayName("下载流保持未知长度且内容未提前读取")
  void shouldKeepDownloadLengthUnknown() throws Exception {
    when(applicationService.download("t1", 1L, null))
        .thenReturn(new FileDownloadArtifact(
            new ByteArrayInputStream("data".getBytes()), "test.csv", "application/octet-stream", true));
    ResponseEntity<InputStreamResource> response =
        new ConsoleFileDownloadController(applicationService).download(1L, "t1", null);
    assertThat(response.getBody().contentLength()).isEqualTo(-1);
    assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo("data".getBytes());
  }

  @Test
  @DisplayName("导出错误记录时保留 CSV 类型、附件名称和内容")
  void shouldReturnCsvExport() throws Exception {
    when(applicationService.exportFileErrors("t1", 1L, null))
        .thenReturn(new FileDownloadArtifact(
            new ByteArrayInputStream("header\nrow\n".getBytes()), "file-errors-1.csv", "text/csv", false));
    mockMvc.perform(get("/api/console/files/1/errors/export").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv"))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"file-errors-1.csv\""))
        .andExpect(content().string("header\nrow\n"));
  }

  @Test
  @DisplayName("无效媒体类型保持下载错误并关闭已打开的流")
  void shouldCloseStreamWhenMediaTypeIsInvalid() {
    AtomicBoolean closed = new AtomicBoolean();
    ByteArrayInputStream stream = new ByteArrayInputStream(new byte[] {1}) {
      @Override
      public void close() throws IOException {
        closed.set(true);
        super.close();
      }
    };
    when(applicationService.download("t1", 1L, null))
        .thenReturn(new FileDownloadArtifact(stream, "file.bin", "invalid/type/extra", true));
    ConsoleFileDownloadController controller = new ConsoleFileDownloadController(applicationService);
    assertThatThrownBy(() -> controller.download(1L, "t1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("failed to open download stream");
    assertThat(closed).isTrue();
  }

  @Test
  @DisplayName("缺少租户参数时返回 400")
  void shouldReturn400WhenTenantIdMissing() throws Exception {
    mockMvc.perform(get("/api/console/files/1/download")).andExpect(status().isBadRequest());
  }
}
