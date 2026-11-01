package io.github.pinpols.batch.console.domain.rbac.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.response.ExcelDownload;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@DisplayName("租户配置包下载：HTTP 头与延迟写入")
class ConsoleTenantConfigPackageExcelDownloadControllerTest {
  @Test
  @DisplayName("五类下载保留文件名、Excel 类型和流式内容")
  void shouldAdaptEachDownloadToTheSameHttpContract() throws Exception {
    TenantConfigPackageExcelService service = mock(TenantConfigPackageExcelService.class);
    ConsoleTenantConfigPackageExcelController controller = new ConsoleTenantConfigPackageExcelController(
        service, mock(ConsoleResponseFactory.class));
    ExcelDownload download = new ExcelDownload("config.xlsx", out -> out.write("data".getBytes(StandardCharsets.UTF_8)));
    when(service.exportPackage("t1")).thenReturn(download);
    when(service.downloadTemplate()).thenReturn(download);
    when(service.downloadScenarioTemplate(List.of("one"))).thenReturn(download);
    when(service.downloadSampleTemplate("one", List.of("two"))).thenReturn(download);
    when(service.downloadPreviewWorkbook("token")).thenReturn(download);

    List<ResponseEntity<StreamingResponseBody>> responses = List.of(
        controller.export("t1"), controller.template(), controller.scenarioTemplate(List.of("one")),
        controller.sampleTemplate("one", List.of("two")), controller.previewWorkbook("token"));
    for (ResponseEntity<StreamingResponseBody> response : responses) {
      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
          .isEqualTo("attachment; filename=\"config.xlsx\"");
      assertThat(response.getHeaders().getContentType().toString())
          .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      response.getBody().writeTo(output);
      assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("data");
    }
  }
}
