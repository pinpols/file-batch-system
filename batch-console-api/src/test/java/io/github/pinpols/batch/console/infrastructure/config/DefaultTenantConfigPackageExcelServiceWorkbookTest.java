package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PIPELINE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.STEP_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.JOB_MONITORING_POLICY_COLUMNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelGuideResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("租户配置包工作簿临时文件处理")
class DefaultTenantConfigPackageExcelServiceWorkbookTest {

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  @Mock
  private TenantConfigPackageExcelImportStore importStore;

  @Mock
  private BatchDateTimeSupport dateTimeSupport;

  @Mock
  private MessageSource messageSource;

  @InjectMocks
  private DefaultTenantConfigPackageExcelService service;

  private void stubUploadContext() {
    when(tenantGuard.resolveTenant(anyString())).thenReturn("ta");
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", "ta", "operator", "idem", null));
  }

  @Test
  @DisplayName("上传成功后从临时文件解析并删除文件")
  void shouldDeleteTemporaryFile_whenUploadSucceeds() throws Exception {
    stubUploadContext();
    MockMultipartFile file = spy(new MockMultipartFile(
        "file",
        "package.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        minimalWorkbook()));
    when(dateTimeSupport.nowInstant()).thenReturn(Instant.parse("2026-10-11T00:00:00Z"));
    when(importStore.save(any())).thenReturn("upload-token");
    ArgumentCaptor<Path> temporaryFile = ArgumentCaptor.forClass(Path.class);

    service.upload(file, "ta");

    verify(file).transferTo(temporaryFile.capture());
    verify(file, never()).getBytes();
    assertThat(temporaryFile.getValue().getParent().getFileName())
        .hasToString("file-batch-private");
    assertThat(temporaryFile.getValue()).doesNotExist();
  }

  @Test
  @DisplayName("工作簿解析失败后仍删除临时文件")
  void shouldDeleteTemporaryFile_whenWorkbookParsingFails() throws Exception {
    stubUploadContext();
    byte[] invalidWorkbook = {'P', 'K', 3, 4, 0, 0, 0, 0};
    MockMultipartFile file = spy(new MockMultipartFile(
        "file",
        "invalid.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        invalidWorkbook));
    ArgumentCaptor<Path> temporaryFile = ArgumentCaptor.forClass(Path.class);

    assertThatThrownBy(() -> service.upload(file, "ta")).isInstanceOf(BizException.class);

    verify(file).transferTo(temporaryFile.capture());
    assertThat(temporaryFile.getValue().getParent().getFileName())
        .hasToString("file-batch-private");
    assertThat(temporaryFile.getValue()).doesNotExist();
  }

  @Test
  @DisplayName("配置字段指南不公开已退役的租户 Pipeline 工作表")
  void shouldExcludeRetiredPipelineSheetsFromGuide() {
    var sheetNames = service.guide().sheets().stream()
        .map(TenantConfigPackageExcelGuideResponse.SheetGuide::sheetName)
        .toList();

    assertThat(sheetNames)
        .doesNotContain(PIPELINE_SHEET, STEP_SHEET)
        .contains("workflow_monitoring_policy")
        .hasSize(11);
  }

  private static byte[] minimalWorkbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet(JOB_MONITORING_POLICY_SHEET);
      var header = sheet.createRow(0);
      for (int i = 0; i < JOB_MONITORING_POLICY_COLUMNS.size(); i++) {
        header.createCell(i).setCellValue(JOB_MONITORING_POLICY_COLUMNS.get(i));
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
