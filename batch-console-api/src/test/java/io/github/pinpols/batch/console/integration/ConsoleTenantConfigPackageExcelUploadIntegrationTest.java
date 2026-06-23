package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleTenantConfigPackageExcelUploadIntegrationTest extends AbstractIntegrationTest {

  private static final String EXCEL_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @LocalServerPort private int port;

  @Test
  void shouldUploadMultipartTenantPackageWorkbookIncludingFileSheets() throws Exception {
    byte[] workbook = tenantPackageWorkbook();
    String boundary = "excel-upload-it-boundary";
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-upload-it")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbook)))
            .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).contains("\"code\":\"SUCCESS\"");
    assertThat(response.body()).contains("\"fileName\":\"tenant-package.xlsx\"");
    assertThat(response.body()).contains("\"fileChannelRows\":1");
    assertThat(response.body()).contains("\"fileTemplateRows\":1");
    assertThat(response.body()).contains("\"jobRows\":0");
    assertThat(response.body()).contains("\"workflowEdgeRows\":0");
  }

  private static byte[] multipartBody(String boundary, byte[] workbook) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writePart(
        out, boundary, "tenantId", null, null, "excel-upload-ta".getBytes(StandardCharsets.UTF_8));
    writePart(out, boundary, "file", "tenant-package.xlsx", EXCEL_CONTENT_TYPE, workbook);
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static void writePart(
      ByteArrayOutputStream out,
      String boundary,
      String name,
      String filename,
      String contentType,
      byte[] content)
      throws Exception {
    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(
        ("Content-Disposition: form-data; name=\"" + name + "\"").getBytes(StandardCharsets.UTF_8));
    if (filename != null) {
      out.write(("; filename=\"" + filename + "\"").getBytes(StandardCharsets.UTF_8));
    }
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    if (contentType != null) {
      out.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    out.write(content);
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] tenantPackageWorkbook() {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      sheet(
          workbook,
          ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET,
          ConfigPackageExcelSchema.ResourceQueue.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
          ConfigPackageExcelSchema.BusinessCalendar.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.BATCH_WINDOW_SHEET,
          ConfigPackageExcelSchema.BatchWindow.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.JOB_SHEET,
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.CHANNEL_SHEET,
          ConfigPackageExcelSchema.FileChannel.COLUMNS,
          List.of(
              row(
                  "tenant_id", "excel-upload-ta",
                  "channel_code", "excel_upload_local",
                  "channel_name", "Excel Upload Local",
                  "channel_type", "LOCAL",
                  "target_endpoint", "/tmp/excel-upload",
                  "auth_type", "NONE",
                  "config_json", "{\"target_endpoint\":\"/tmp/excel-upload\"}",
                  "receipt_policy", "NONE",
                  "timeout_seconds", "30",
                  "enabled", "true")));
      sheet(
          workbook,
          ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
          ConfigPackageExcelSchema.FileTemplate.COLUMNS,
          List.of(
              row(
                  "tenant_id", "excel-upload-ta",
                  "template_code", "EXCEL_UPLOAD_TEMPLATE",
                  "template_name", "Excel Upload Template",
                  "template_type", "IMPORT",
                  "biz_type", "SETTLEMENT",
                  "file_format_type", "DELIMITED",
                  "charset", "UTF-8",
                  "target_charset", "UTF-8",
                  "with_bom", "false",
                  "line_separator", "\\n",
                  "delimiter", ",",
                  "quote_char", "\"",
                  "escape_char", "\\",
                  "record_length", "",
                  "header_rows", "1",
                  "footer_rows", "0",
                  "checksum_type", "NONE",
                  "compress_type", "NONE",
                  "encrypt_type", "NONE",
                  "field_mappings", "[]",
                  "validation_rule_set", "{}",
                  "query_param_schema", "{}",
                  "streaming_enabled", "false",
                  "page_size", "1000",
                  "fetch_size", "1000",
                  "chunk_size", "1000",
                  "preview_masking_enabled", "false",
                  "error_line_masking_enabled", "false",
                  "log_masking_enabled", "false",
                  "content_encryption_enabled", "false",
                  "download_requires_approval", "false",
                  "enabled", "true",
                  "version", "1")));
      sheet(
          workbook,
          ConfigPackageExcelValidator.PIPELINE_SHEET,
          ConfigPackageExcelSchema.PipelineDefinition.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.STEP_SHEET,
          ConfigPackageExcelSchema.PipelineStep.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.WF_DEF_SHEET,
          ConfigPackageExcelSchema.WorkflowDefinition.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.WF_NODE_SHEET,
          ConfigPackageExcelSchema.WorkflowNode.COLUMNS,
          List.of());
      sheet(
          workbook,
          ConfigPackageExcelValidator.WF_EDGE_SHEET,
          ConfigPackageExcelSchema.WorkflowEdge.COLUMNS,
          List.of());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("failed to build tenant package workbook", e);
    }
  }

  private static void sheet(
      XSSFWorkbook workbook,
      String sheetName,
      List<String> columns,
      List<Map<String, String>> rows) {
    Sheet sheet = workbook.createSheet(sheetName);
    Row header = sheet.createRow(0);
    for (int i = 0; i < columns.size(); i++) {
      header.createCell(i).setCellValue(columns.get(i));
    }
    for (int r = 0; r < rows.size(); r++) {
      Row row = sheet.createRow(r + 1);
      Map<String, String> values = rows.get(r);
      for (int c = 0; c < columns.size(); c++) {
        row.createCell(c).setCellValue(values.getOrDefault(columns.get(c), ""));
      }
    }
  }

  private static Map<String, String> row(String... values) {
    Map<String, String> row = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      row.put(values[i], values[i + 1]);
    }
    return row;
  }
}
