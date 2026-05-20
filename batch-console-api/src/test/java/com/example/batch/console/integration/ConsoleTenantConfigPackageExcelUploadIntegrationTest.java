package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleTenantConfigPackageExcelUploadIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
  }

  @Test
  void shouldUploadMultipartTenantPackageWorkbookIncludingFileSheets() {
    byte[] workbook = tenantPackageWorkbook();
    MultipartBodyBuilder body = new MultipartBodyBuilder();
    body.part(
            "file",
            new ByteArrayResource(workbook) {
              @Override
              public String getFilename() {
                return "tenant-package.xlsx";
              }
            })
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    body.part("tenantId", "excel-upload-ta");

    client
        .post()
        .uri("/api/console/config/tenant-package/excel/upload")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-upload-it")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(body.build()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            response -> {
              assertThat(response).contains("\"code\":\"SUCCESS\"");
              assertThat(response).contains("\"fileName\":\"tenant-package.xlsx\"");
              assertThat(response).contains("\"fileChannelRows\":1");
              assertThat(response).contains("\"fileTemplateRows\":1");
              assertThat(response).contains("\"jobRows\":0");
              assertThat(response).contains("\"workflowEdgeRows\":0");
            });
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
