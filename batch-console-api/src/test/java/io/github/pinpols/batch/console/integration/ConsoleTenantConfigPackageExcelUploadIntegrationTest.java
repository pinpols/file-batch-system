package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageSampleDataFactory;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageSheetSpecs;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageWorkbookLimits;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.console.security.rate-limit.expensive-op-user-limit-per-minute=1000")
@DisplayName("租户配置包 Excel 上传: 解析成功并回显各工作表命中行数")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConsoleTenantConfigPackageExcelUploadIntegrationTest extends AbstractIntegrationTest {

  private static final String EXCEL_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @LocalServerPort
  private int port;

  private final JdbcTemplate jdbcTemplate;

  private final StringRedisTemplate redisTemplate;

  ConsoleTenantConfigPackageExcelUploadIntegrationTest(
      JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.redisTemplate = redisTemplate;
  }

  @Test
  @DisplayName("上传租户配置包: 返回成功,并回显文件通道与文件模板各命中一行")
  void shouldUploadMultipartTenantPackageWorkbookIncludingFileSheets() throws Exception {
    byte[] workbook = tenantPackageWorkbook(true);
    String boundary = "excel-upload-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
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

  @Test
  @DisplayName("拒绝包含公式单元格的配置包")
  void shouldRejectWorkbookContainingFormulaCells() throws Exception {
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet channels = workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET);
      Row header = channels.getRow(0);
      Row firstChannel = channels.getRow(1);
      firstChannel
          .getCell(columnIndex(header, "channel_name"))
          .setCellFormula("\"Calculated channel name\"");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }
    String boundary = "excel-formula-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-formula-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("formula cells are not allowed");
  }

  @Test
  @DisplayName("拒绝包含外部工作簿链接的配置包")
  void shouldRejectWorkbookContainingExternalLinks() throws Exception {
    byte[] workbookBytes;
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFWorkbook linkedWorkbook = new XSSFWorkbook()) {
      linkedWorkbook.createSheet("ExternalData");
      workbook.createSheet(ConfigPackageExcelValidator.CHANNEL_SHEET);
      workbook.linkExternalWorkbook("external-data.xlsx", linkedWorkbook);
      assertThat(workbook
              .getPackagePart()
              .getRelationshipsByType(XSSFRelation.EXTERNAL_LINKS.getRelation())
              .iterator()
              .hasNext())
          .isTrue();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }
    String boundary = "excel-external-link-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-external-link-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("external links and macros are not allowed");
  }

  @Test
  @DisplayName("拒绝宏启用工作簿扩展名")
  void shouldRejectMacroEnabledWorkbookExtension() throws Exception {
    String boundary = "excel-macro-it-boundary";
    byte[] requestBody = multipartBody(
        boundary, tenantPackageWorkbook(true), "excel-upload-ta", "tenant-package.xlsm");
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-macro-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
  }

  @Test
  @DisplayName("即使扩展名为 xlsx 也拒绝含 VBA 项目关系的工作簿")
  void shouldRejectVbaProjectHiddenBehindXlsxExtension() throws Exception {
    String boundary = "excel-hidden-vba-it-boundary";
    byte[] workbookWithVba = withVbaProjectRelationship(tenantPackageWorkbook(true));
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-hidden-vba-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(
            multipartBody(boundary, workbookWithVba, "excel-upload-ta", "tenant-package.xlsx")))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("external links and macros are not allowed");
  }

  @Test
  @DisplayName("损坏工作簿返回通用错误,不回显解析器内部信息")
  void shouldNotExposeWorkbookParserDetailsForMalformedUpload() throws Exception {
    byte[] malformedWorkbook = {
      0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04
    };
    String boundary = "excel-malformed-workbook-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-malformed-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, malformedWorkbook)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("failed to read excel workbook");
    assertThat(response.body()).doesNotContain("ZipException", "java.io");
  }

  @Test
  @DisplayName("拒绝超出配置包最大行号的工作簿")
  void shouldRejectWorkbookExceedingMaximumRowIndex() throws Exception {
    byte[] workbookBytes;
    try (XSSFWorkbook workbook =
        new XSSFWorkbook(new ByteArrayInputStream(tenantPackageWorkbook(true)))) {
      workbook
          .getSheet(ConfigPackageExcelValidator.JOB_SHEET)
          .createRow(ConfigPackageWorkbookLimits.MAX_ROW_INDEX + 1)
          .createCell(0)
          .setCellValue("out of range");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }

    String boundary = "excel-row-limit-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-row-limit-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("maximum row index");
  }

  @Test
  @DisplayName("拒绝解压后超过 POI 条目上限的工作簿")
  void shouldRejectWorkbookWithOversizedInflatedZipEntry() throws Exception {
    byte[] workbookBytes = workbookWithOversizedSharedStringsEntry();
    assertThat(workbookBytes.length).isLessThan(1024 * 1024);

    String boundary = "excel-zip-entry-limit-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-zip-entry-limit-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("failed to read excel workbook");
    assertThat(response.body()).doesNotContain("Zip bomb detected", "ZipSecureFile");
  }

  @Test
  @DisplayName("拒绝配置 Sheet 中未识别的表头，避免字段拼写错误被静默忽略")
  void shouldRejectWorkbookContainingUnknownHeader() throws Exception {
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet channels = workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET);
      Row header = channels.getRow(0);
      header.createCell(header.getLastCellNum()).setCellValue("channel_nam");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }
    String boundary = "excel-unknown-header-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-unknown-header-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("unsupported headers", "channel_nam");
  }

  @Test
  @DisplayName("拒绝配置包中的未知工作表，避免用户误以为数据已导入")
  void shouldRejectWorkbookContainingUnknownSheet() throws Exception {
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      workbook.createSheet("tenant_notes").createRow(0).createCell(0).setCellValue("notes");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }
    String boundary = "excel-unknown-sheet-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-unknown-sheet-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("unsupported excel sheet", "tenant_notes");
  }

  @Test
  @DisplayName("拒绝配置 Sheet 中重复表头，避免后列覆盖前列")
  void shouldRejectWorkbookContainingDuplicateHeader() throws Exception {
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet channels = workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET);
      Row header = channels.getRow(0);
      header.createCell(header.getLastCellNum()).setCellValue("channel_code");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }
    String boundary = "excel-duplicate-header-it-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-duplicate-header-reject")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbookBytes)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(response.body()).contains("duplicate headers", "channel_code");
  }

  @Test
  @DisplayName("配置包会话绑定上传账号和权限范围")
  void shouldRestrictUploadSessionToUploaderAndPermissionScope() throws Exception {
    String tenantId = "excel-session-owner-it";
    String boundary = "excel-session-owner-boundary";
    HttpRequest uploadRequest = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-session-owner")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-User", "package-owner")
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .POST(HttpRequest.BodyPublishers.ofByteArray(
            multipartBody(boundary, tenantPackageWorkbook(true), tenantId)))
        .build();
    HttpResponse<String> uploadResponse =
        HttpClient.newHttpClient().send(uploadRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(uploadResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    String token = new ObjectMapper()
        .readTree(uploadResponse.body())
        .path("data")
        .path("uploadToken")
        .asText();

    HttpResponse<String> otherUserPreview =
        previewTokenAs(token, tenantId, "another-user", "ROLE_TENANT_ADMIN");
    assertThat(otherUserPreview.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertPreviewTokenUnchanged(token, "");

    HttpResponse<String> changedScopePreview =
        previewTokenAs(token, tenantId, "package-owner", "ROLE_ADMIN");
    assertThat(changedScopePreview.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertPreviewTokenUnchanged(token, "");

    HttpResponse<String> ownerPreview =
        previewTokenAs(token, tenantId, "package-owner", "ROLE_TENANT_ADMIN");
    assertThat(ownerPreview.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(ownerPreview.body()).contains("\"code\":\"SUCCESS\"");
  }

  private void assertPreviewTokenUnchanged(String uploadToken, String expected) {
    String key = "batch:console:tenant-config-upload:" + uploadToken;
    assertThat(redisTemplate.<String, String>opsForHash().get(key, "preview_token"))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("租户配置包导出不含通道密码且同租户回导保留数据库凭据")
  void shouldExcludeChannelCredentialsFromExportAndPreserveThemOnMerge() throws Exception {
    String tenantId = "channel-export-security-it";
    String channelCode = "SFTP_EXPORT_SECRET";
    String jobCode = "JOB_ROUTE_EXPORT_SECRET";
    String queueCode = "PLATFORM_ROUTE_EXPORT_SECRET";
    String secretConfig = "{\"sftp_host\":\"sftp.example.com\",\"sftp_user\":\"batch\","
        + "\"sftp_password\":\"plain-text-secret\"}";
    jdbcTemplate.update(
        "insert into batch.file_channel_config (tenant_id, channel_code, channel_name, "
            + "channel_type, target_endpoint, auth_type, config_json, receipt_policy, "
            + "timeout_seconds, enabled) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
        tenantId,
        channelCode,
        "SFTP export security",
        "SFTP",
        "sftp://operator:embedded-password@sftp.example.com/inbox?token=embedded-token",
        "PASSWORD",
        secretConfig,
        "NONE",
        30,
        true);
    jdbcTemplate.update(
        "insert into batch.resource_queue "
            + "(tenant_id, queue_code, queue_name, queue_type, worker_group, resource_tag) "
            + "values (?, ?, ?, 'IMPORT', 'PLATFORM_IMPORT_POOL', 'sensitive-capacity-tag')",
        tenantId,
        queueCode,
        "Platform route export security");
    jdbcTemplate.update(
        "insert into batch.job_definition "
            + "(tenant_id, job_code, job_name, job_type, schedule_type, timezone, queue_code, "
            + "worker_group, default_params) values (?, ?, ?, 'IMPORT', 'MANUAL', "
            + "'Asia/Shanghai', ?, ?, ?::jsonb)",
        tenantId,
        jobCode,
        "Platform route export security",
        queueCode,
        "PLATFORM_IMPORT_POOL",
        "{\"password\":\"job-embedded-secret\",\"region\":\"west\"}");

    HttpRequest exportRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/export?tenantId=" + tenantId))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .GET()
        .build();
    HttpResponse<byte[]> exportResponse =
        HttpClient.newHttpClient().send(exportRequest, HttpResponse.BodyHandlers.ofByteArray());

    assertThat(exportResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    byte[] exportedWorkbook = exportResponse.body();
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exportedWorkbook))) {
      Sheet queues = workbook.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET);
      assertThat(queues.getPhysicalNumberOfRows()).isEqualTo(1);

      Sheet jobs = workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      Row jobHeader = jobs.getRow(0);
      Row job = rowForValue(jobs, columnIndex(jobHeader, "job_code"), jobCode);
      assertThat(job.getCell(columnIndex(jobHeader, "queue_code")).toString()).isBlank();
      assertThat(job.getCell(columnIndex(jobHeader, "worker_group")).toString()).isBlank();
      assertThat(job.getCell(columnIndex(jobHeader, "default_params")).toString())
          .contains("\"password\":\"****\"")
          .doesNotContain("job-embedded-secret");

      Sheet channels = workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET);
      Row header = channels.getRow(0);
      Row channel = rowForValue(channels, columnIndex(header, "channel_code"), channelCode);
      assertThat(channel.getCell(columnIndex(header, "config_json")).toString()).isBlank();
      assertThat(channel.getCell(columnIndex(header, "target_endpoint")).toString())
          .isBlank();
    }

    HttpResponse<String> applyResponse = applyWorkbookAsRole(
        exportedWorkbook, "channel-export-roundtrip", "ROLE_TENANT_ADMIN", tenantId);

    assertThat(applyResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    String persistedConfig = jdbcTemplate.queryForObject(
        "select config_json::text from batch.file_channel_config "
            + "where tenant_id = ? and channel_code = ?",
        String.class,
        tenantId,
        channelCode);
    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.readTree(persistedConfig)).isEqualTo(mapper.readTree(secretConfig));
    String persistedEndpoint = jdbcTemplate.queryForObject(
        "select target_endpoint from batch.file_channel_config "
            + "where tenant_id = ? and channel_code = ?",
        String.class,
        tenantId,
        channelCode);
    assertThat(persistedEndpoint)
        .isEqualTo("sftp://operator:embedded-password@sftp.example.com/inbox?token=embedded-token");
    assertThat(jdbcTemplate.queryForObject(
            "select queue_code || ':' || worker_group from batch.job_definition "
                + "where tenant_id = ? and job_code = ?",
            String.class,
            tenantId,
            jobCode))
        .isEqualTo(queueCode + ":PLATFORM_IMPORT_POOL");
    assertThat(jdbcTemplate.queryForObject(
            "select default_params ->> 'password' from batch.job_definition "
                + "where tenant_id = ? and job_code = ?",
            String.class,
            tenantId,
            jobCode))
        .isEqualTo("job-embedded-secret");

    HttpRequest adminExportRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/export?tenantId=" + tenantId))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", "ROLE_ADMIN")
        .GET()
        .build();
    HttpResponse<byte[]> adminExportResponse = HttpClient.newHttpClient()
        .send(adminExportRequest, HttpResponse.BodyHandlers.ofByteArray());
    assertThat(adminExportResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    try (XSSFWorkbook workbook =
        new XSSFWorkbook(new ByteArrayInputStream(adminExportResponse.body()))) {
      Sheet queues = workbook.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET);
      Row queueHeader = queues.getRow(0);
      Row queue = rowForValue(queues, columnIndex(queueHeader, "queue_code"), queueCode);
      assertThat(queue).isNotNull();
      Sheet jobs = workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      Row jobHeader = jobs.getRow(0);
      Row job = rowForValue(jobs, columnIndex(jobHeader, "job_code"), jobCode);
      assertThat(job.getCell(columnIndex(jobHeader, "queue_code")).toString()).isEqualTo(queueCode);
      assertThat(job.getCell(columnIndex(jobHeader, "worker_group")).toString())
          .isEqualTo("PLATFORM_IMPORT_POOL");
    }
  }

  @Test
  @DisplayName("上传缺少新增监控策略 sheet 的旧配置包: 保留兼容导入")
  void shouldAcceptLegacyWorkbookMissingJobMonitoringPolicySheet() throws Exception {
    byte[] workbook = tenantPackageWorkbook(false);
    String boundary = "excel-upload-missing-monitoring-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-upload-missing-monitoring")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbook)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).contains("\"code\":\"SUCCESS\"");
    assertThat(response.body()).contains("\"fileChannelRows\":1");
    assertThat(response.body()).contains("\"fileTemplateRows\":1");
  }

  @Test
  @DisplayName("真实 HTTP 场景模板下载: IMPORT+EXPORT 保留必需配置列且不暴露平台资源列")
  void shouldDownloadFocusedWorkbookWithImportAndExportColumns() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/scenario-template"
            + "?scenarios=IMPORT&scenarios=EXPORT"))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "excel-upload-ta")
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .GET()
        .build();

    HttpResponse<byte[]> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
        .contains("tenant-config-package-export-import.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET)).isNotNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET))
          .isNotNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.PIPELINE_SHEET)).isNotNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.STEP_SHEET)).isNotNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET)).isNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET))
          .isNull();

      assertHeaderColumns(
          workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET),
          Set.of("job_code", "job_name", "job_type", "schedule_type", "default_params"));
      assertHeaderColumns(
          workbook.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET),
          Set.of(
              "template_code",
              "file_format_type",
              "charset",
              "target_charset",
              "delimiter",
              "header_rows",
              "query_param_schema",
              "default_query_sql"));
      assertHeaderColumns(
          workbook.getSheet(ConfigPackageExcelValidator.STEP_SHEET),
          Set.of("job_code", "step_code", "stage_code", "step_params"));
    }
  }

  @Test
  @DisplayName("场景化示例工作簿可直接上传预览，且不依赖资源队列与监控阈值 sheet")
  void shouldUploadScenarioWorkbookGeneratedByTemplateEndpointContract() throws Exception {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer =
        new ConfigPackageExcelWorkbookWriter(messages, List.of("IMPORT"));
    byte[] workbook =
        writer.buildExportWorkbook(ConfigPackageSampleDataFactory.sampleSheets("IMPORT"));
    String boundary = "excel-upload-scenario-boundary";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-excel-upload-scenario")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbook)))
        .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).contains("\"code\":\"SUCCESS\"");
    assertThat(response.body()).contains("\"jobRows\":1");
    assertThat(response.body()).contains("\"fileTemplateRows\":1");
    assertThat(response.body()).contains("\"pipelineRows\":1");
    assertThat(response.body()).contains("\"pipelineStepRows\":6");
    assertThat(response.body()).contains("\"resourceQueueRows\":0");

    JsonNode uploadResponse = new ObjectMapper().readTree(response.body());
    String uploadToken = uploadResponse.path("data").path("uploadToken").asText();
    HttpResponse<String> previewResponse = HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/console/config/tenant-package/excel/preview/" + uploadToken))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(previewResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    JsonNode preview = new ObjectMapper().readTree(previewResponse.body());
    assertThat(preview.path("code").asText()).isEqualTo("SUCCESS");
    assertThat(preview.path("data").path("invalidRows").asInt())
        .as("Scenario template validation issues: %s", preview.path("data").path("issues"))
        .isZero();
    assertThat(preview.path("data").path("issues")).isEmpty();
    String previewToken = preview.path("data").path("previewToken").asText();
    assertThat(previewToken).isNotBlank();

    HttpResponse<String> refreshedPreviewResponse = HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/console/config/tenant-package/excel/preview/" + uploadToken))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String refreshedPreviewToken = new ObjectMapper()
        .readTree(refreshedPreviewResponse.body())
        .path("data")
        .path("previewToken")
        .asText();
    assertThat(refreshedPreviewToken).isNotBlank().isNotEqualTo(previewToken);

    HttpResponse<String> staleApplyResponse =
        postApply(uploadToken, previewToken, "idem-stale-preview");
    assertThat(staleApplyResponse.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

    HttpResponse<String> applyResponse =
        postApply(uploadToken, refreshedPreviewToken, "idem-excel-apply-scenario");

    assertThat(applyResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    JsonNode applied = new ObjectMapper().readTree(applyResponse.body());
    assertThat(applied.path("code").asText()).isEqualTo("SUCCESS");
    assertThat(applied.path("data").path("jobInserted").asInt()).isEqualTo(1);
    assertThat(applied.path("data").path("fileTemplateInserted").asInt()).isEqualTo(1);
    assertThat(applied.path("data").path("pipelineInserted").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("跨工作表引用错误计入预览无效行数")
  void shouldCountCrossSheetReferenceErrorsAsInvalidRowsInPreview() throws Exception {
    String missingJobCode = "MISSING_" + UUID.randomUUID().toString().replace("-", "");
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet jobs = workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      appendDataRow(
          jobs,
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          row(
              "tenant_id", "excel-upload-ta",
              "job_code", "JOB_IMPORT_REFERENCE_CHECK",
              "job_name", "Reference check",
              "job_type", "IMPORT",
              "schedule_type", "MANUAL",
              "depends_on_job_code", missingJobCode,
              "param_schema", "{}",
              "default_params", "{}",
              "enabled", "true"));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }

    HttpResponse<String> response = previewWorkbookAsRole(
        workbookBytes, "cross-sheet-reference-preview", "ROLE_ADMIN", "excel-upload-ta");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    JsonNode preview = new ObjectMapper().readTree(response.body()).path("data");
    assertThat(preview.path("invalidRows").asInt())
        .as("cross-sheet reference must be blocking: %s", preview)
        .isEqualTo(1);
    assertThat(preview.path("validRows").asInt())
        .isEqualTo(preview.path("totalRows").asInt() - 1);
    JsonNode jobStats = null;
    for (JsonNode sheet : preview.path("sheets")) {
      if ("job_definition".equals(sheet.path("sheetName").asText())) {
        jobStats = sheet;
        break;
      }
    }
    assertThat(jobStats).isNotNull();
    assertThat(jobStats.path("invalidRows").asInt()).isEqualTo(1);
    assertThat(preview.path("issues").toString()).contains(missingJobCode);
  }

  @Test
  @DisplayName("配置包可导入多上游依赖并按配置顺序持久化")
  void shouldApplyMultipleJobDependenciesFromPackage() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    String downstream = "JOB_DOWNSTREAM_" + suffix;
    String upstreamOne = "JOB_UPSTREAM_A_" + suffix;
    String upstreamTwo = "JOB_UPSTREAM_B_" + suffix;
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet jobs = workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      appendDataRow(
          jobs,
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          row(
              "tenant_id", "excel-upload-ta",
              "job_code", downstream,
              "job_name", "Downstream",
              "job_type", "IMPORT",
              "schedule_type", "CRON",
              "schedule_expr", "0 0 2 * * ?",
              "depends_on_job_codes", upstreamOne + "," + upstreamTwo,
              "depends_on_biz_date_alignments", "SAME_DAY,PREVIOUS_BIZ_DAY",
              "param_schema", "{}",
              "default_params", "{}",
              "enabled", "true"));
      appendDataRow(
          jobs,
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          row(
              "tenant_id", "excel-upload-ta",
              "job_code", upstreamOne,
              "job_name", "Upstream A",
              "job_type", "IMPORT",
              "schedule_type", "MANUAL",
              "param_schema", "{}",
              "default_params", "{}",
              "enabled", "true"));
      appendDataRow(
          jobs,
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          row(
              "tenant_id", "excel-upload-ta",
              "job_code", upstreamTwo,
              "job_name", "Upstream B",
              "job_type", "IMPORT",
              "schedule_type", "MANUAL",
              "param_schema", "{}",
              "default_params", "{}",
              "enabled", "true"));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }

    HttpResponse<String> previewResponse = previewWorkbookAsRole(
        workbookBytes,
        "multi-dependency-package-" + suffix.toLowerCase(Locale.ROOT),
        "ROLE_ADMIN",
        "excel-upload-ta");
    JsonNode preview = new ObjectMapper().readTree(previewResponse.body()).path("data");
    assertThat(preview.path("invalidRows").asInt())
        .as("multi-dependency package preview: %s", preview)
        .isZero();
    HttpResponse<String> applyResponse = postApply(
        preview.path("uploadToken").asText(),
        preview.path("previewToken").asText(),
        "idem-multi-dependency-" + suffix.toLowerCase(Locale.ROOT));
    assertThat(applyResponse.statusCode())
        .as("multi-dependency package apply response: %s", applyResponse.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(jdbcTemplate.queryForList(
            "select upstream_job_code from batch.job_dependency "
                + "where tenant_id = ? and downstream_job_code = ? order by dependency_order",
            String.class,
            "excel-upload-ta",
            downstream))
        .containsExactly(upstreamOne, upstreamTwo);
    assertThat(jdbcTemplate.queryForList(
            "select biz_date_alignment from batch.job_dependency "
                + "where tenant_id = ? and downstream_job_code = ? order by dependency_order",
            String.class,
            "excel-upload-ta",
            downstream))
        .containsExactly("SAME_DAY", "PREVIOUS_BIZ_DAY");
  }

  @Test
  @DisplayName("配置引用大小写规范化后仍可导出并通过回导预览")
  void shouldNormalizeConfigReferencesAndPreserveExportRoundTrip() throws Exception {
    String jobCode = "JOB_REF_NORMALIZE_" + UUID.randomUUID().toString().replace("-", "");
    byte[] workbookBytes = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      appendDataRow(
          workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET),
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          row(
              "tenant_id", "excel-upload-ta",
              "job_code", jobCode,
              "job_name", "Reference normalization",
              "job_type", "IMPORT",
              "schedule_type", "MANUAL",
              "default_params", "{\"templateCode\":\"EXCEL_UPLOAD_TEMPLATE\"}",
              "param_schema", "{\"template_code\":\"EXCEL_UPLOAD_TEMPLATE\"}",
              "enabled", "true"));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }

    HttpResponse<String> applyResponse = applyWorkbookAsRole(
        workbookBytes, "normalize-config-references", "ROLE_ADMIN", "excel-upload-ta");
    assertThat(applyResponse.statusCode())
        .as("configuration package apply response: %s", applyResponse.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(jdbcTemplate.queryForObject(
            "select default_params->>'templateCode' from batch.job_definition "
                + "where tenant_id = ? and job_code = ?",
            String.class,
            "excel-upload-ta",
            jobCode))
        .isEqualTo("excel_upload_template");
    assertThat(jdbcTemplate.queryForObject(
            "select param_schema->>'template_code' from batch.job_definition "
                + "where tenant_id = ? and job_code = ?",
            String.class,
            "excel-upload-ta",
            jobCode))
        .isEqualTo("excel_upload_template");

    HttpRequest exportRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/export?tenantId=excel-upload-ta"))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "excel-upload-ta")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .GET()
        .build();
    HttpResponse<byte[]> exportResponse =
        HttpClient.newHttpClient().send(exportRequest, HttpResponse.BodyHandlers.ofByteArray());
    assertThat(exportResponse.statusCode()).isEqualTo(HttpStatus.OK.value());

    HttpResponse<String> previewResponse = previewWorkbookAsRole(
        exportResponse.body(),
        "normalize-config-references-roundtrip",
        "ROLE_ADMIN",
        "excel-upload-ta");
    assertThat(previewResponse.statusCode())
        .as("exported configuration package preview: %s", previewResponse.body())
        .isEqualTo(HttpStatus.OK.value());
    JsonNode preview = new ObjectMapper().readTree(previewResponse.body()).path("data");
    assertThat(preview.path("invalidRows").asInt()).isZero();
    assertThat(preview.path("issues")).isEmpty();
  }

  @Test
  @DisplayName("单场景、组合场景及 ALL 兼容模板均可生成、上传并通过预览")
  void shouldPreviewEveryScenarioTemplateAndLegacyAllTemplate() throws Exception {
    List<List<String>> scenarioSelections = List.of(
        List.of("IMPORT"),
        List.of("EXPORT"),
        List.of("PROCESS"),
        List.of("DISPATCH"),
        List.of("ATOMIC"),
        List.of("WORKFLOW"),
        List.of("SCHEDULE"),
        List.of("IMPORT", "EXPORT"),
        List.of("ALL"));
    for (List<String> selectedScenarios : scenarioSelections) {
      String scenario = String.join("+", selectedScenarios);
      String scenarioQuery = selectedScenarios.stream()
          .map(value -> "scenarios=" + value)
          .collect(Collectors.joining("&"));
      HttpResponse<byte[]> templateResponse = HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port
                      + "/api/console/config/tenant-package/excel/sample-template?"
                      + scenarioQuery))
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertThat(templateResponse.statusCode())
          .as("template download for %s", scenario)
          .isEqualTo(HttpStatus.OK.value());
      assertScenarioTemplateSheets(templateResponse.body(), selectedScenarios);

      String boundary = "excel-upload-scenario-" + scenario.toLowerCase();
      HttpResponse<String> uploadResponse = HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port
                      + "/api/console/config/tenant-package/excel/upload"))
                  .timeout(Duration.ofSeconds(60))
                  .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                  .header(
                      CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
                      "idem-excel-scenario-matrix-" + scenario.toLowerCase())
                  .POST(HttpRequest.BodyPublishers.ofByteArray(
                      multipartBody(boundary, templateResponse.body())))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(uploadResponse.statusCode())
          .as("upload for %s", scenario)
          .isEqualTo(HttpStatus.OK.value());
      JsonNode upload = new ObjectMapper().readTree(uploadResponse.body());
      assertThat(upload.path("code").asText())
          .as("upload result for %s", scenario)
          .isEqualTo("SUCCESS");
      String token = upload.path("data").path("uploadToken").asText();
      assertThat(token).as("upload token for %s", scenario).isNotBlank();

      HttpResponse<String> previewResponse = HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port
                      + "/api/console/config/tenant-package/excel/preview/" + token))
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(previewResponse.statusCode())
          .as("preview for %s", scenario)
          .isEqualTo(HttpStatus.OK.value());
      JsonNode preview = new ObjectMapper().readTree(previewResponse.body());
      assertThat(preview.path("code").asText())
          .as("preview result for %s", scenario)
          .isEqualTo("SUCCESS");
      assertThat(preview.path("data").path("invalidRows").asInt())
          .as(
              "preview validation issues for %s: %s",
              scenario, preview.path("data").path("issues"))
          .isZero();
    }
  }

  private static void assertScenarioTemplateSheets(byte[] workbookBytes, List<String> scenarios)
      throws Exception {
    Set<String> sheets = new LinkedHashSet<>();
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      for (Sheet sheet : workbook) {
        sheets.add(sheet.getSheetName());
      }

      ConfigPackageSheetSpecs specs = new ConfigPackageSheetSpecs(new StaticMessageSource());
      for (ConfigPackageSheetSpecs.SheetDef expectedSheet : specs.project(scenarios)) {
        Sheet actualSheet = workbook.getSheet(expectedSheet.name());
        assertThat(actualSheet).as("projected sheet %s", expectedSheet.name()).isNotNull();

        Row header = actualSheet.getRow(0);
        assertThat(header).as("header row for %s", expectedSheet.name()).isNotNull();
        List<String> actualColumns = new ArrayList<>();
        for (int columnIndex = 0; columnIndex < header.getLastCellNum(); columnIndex++) {
          actualColumns.add(header.getCell(columnIndex).getStringCellValue());
        }
        assertThat(actualColumns)
            .as("complete ordered columns for %s in scenarios %s", expectedSheet.name(), scenarios)
            .containsExactlyElementsOf(expectedSheet.columns());
      }
    }

    assertThat(sheets).contains("job_definition", "字段说明");
    if (scenarios.contains("ALL")) {
      assertThat(sheets)
          .contains(
              ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET,
              ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET,
              ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
              ConfigPackageExcelValidator.BATCH_WINDOW_SHEET,
              ConfigPackageExcelValidator.CHANNEL_SHEET,
              ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
              ConfigPackageExcelValidator.PIPELINE_SHEET,
              ConfigPackageExcelValidator.STEP_SHEET,
              ConfigPackageExcelValidator.WF_DEF_SHEET,
              ConfigPackageExcelValidator.WF_NODE_SHEET,
              ConfigPackageExcelValidator.WF_EDGE_SHEET);
      return;
    }

    if (scenarios.contains("IMPORT") || scenarios.contains("EXPORT")) {
      assertThat(sheets)
          .contains(
              ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
              ConfigPackageExcelValidator.PIPELINE_SHEET,
              ConfigPackageExcelValidator.STEP_SHEET);
    }
    if (scenarios.contains("PROCESS") || scenarios.contains("DISPATCH")) {
      assertThat(sheets)
          .contains(
              ConfigPackageExcelValidator.PIPELINE_SHEET, ConfigPackageExcelValidator.STEP_SHEET);
    }
    if (scenarios.contains("DISPATCH")) {
      assertThat(sheets).contains(ConfigPackageExcelValidator.CHANNEL_SHEET);
    }
    if (scenarios.contains("WORKFLOW")) {
      assertThat(sheets)
          .contains(
              ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
              ConfigPackageExcelValidator.CHANNEL_SHEET,
              ConfigPackageExcelValidator.WF_DEF_SHEET,
              ConfigPackageExcelValidator.WF_NODE_SHEET,
              ConfigPackageExcelValidator.WF_EDGE_SHEET);
    }
    if (scenarios.contains("SCHEDULE")) {
      assertThat(sheets)
          .contains(
              ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
              ConfigPackageExcelValidator.BATCH_WINDOW_SHEET,
              ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET);
    }
    if (scenarios.contains("ATOMIC")) {
      assertThat(sheets)
          .doesNotContain(
              ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
              ConfigPackageExcelValidator.PIPELINE_SHEET,
              ConfigPackageExcelValidator.CHANNEL_SHEET,
              ConfigPackageExcelValidator.WF_DEF_SHEET);
    }
    assertThat(sheets).doesNotContain(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET);
    if (!scenarios.contains("SCHEDULE")) {
      assertThat(sheets).doesNotContain(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET);
    }
  }

  @Test
  @DisplayName("方向与格式场景模板通过真实 HTTP 返回匹配的列和下拉选项")
  void shouldReturnFocusedFileTemplateForEverySupportedFormat() throws Exception {
    Map<String, List<String>> expectedColumns = Map.ofEntries(
        Map.entry("IMPORT_DELIMITED", List.of("delimiter", "quote_char", "validation_rule_set")),
        Map.entry("IMPORT_FIXED_WIDTH", List.of("record_length", "validation_rule_set")),
        Map.entry("IMPORT_EXCEL", List.of("header_rows", "validation_rule_set")),
        Map.entry("IMPORT_XML", List.of("charset", "validation_rule_set")),
        Map.entry("IMPORT_JSON", List.of("validation_rule_set")),
        Map.entry("EXPORT_DELIMITED", List.of("delimiter", "default_query_sql")),
        Map.entry("EXPORT_FIXED_WIDTH", List.of("record_length", "default_query_sql")),
        Map.entry("EXPORT_EXCEL", List.of("header_rows", "default_query_sql")),
        Map.entry("EXPORT_JSON", List.of("default_query_sql", "query_param_schema")));
    Map<String, List<String>> excludedColumns = Map.ofEntries(
        Map.entry("IMPORT_DELIMITED", List.of("record_length", "default_query_sql")),
        Map.entry("IMPORT_FIXED_WIDTH", List.of("delimiter", "quote_char", "default_query_sql")),
        Map.entry("IMPORT_EXCEL", List.of("charset", "delimiter", "record_length")),
        Map.entry(
            "IMPORT_XML",
            List.of("delimiter", "record_length", "header_rows", "default_query_sql")),
        Map.entry("IMPORT_JSON", List.of("delimiter", "record_length", "header_rows")),
        Map.entry("EXPORT_DELIMITED", List.of("record_length", "validation_rule_set")),
        Map.entry("EXPORT_FIXED_WIDTH", List.of("delimiter", "quote_char", "validation_rule_set")),
        Map.entry("EXPORT_EXCEL", List.of("charset", "delimiter", "record_length")),
        Map.entry("EXPORT_JSON", List.of("delimiter", "record_length", "validation_rule_set")));

    for (Map.Entry<String, List<String>> scenario : expectedColumns.entrySet()) {
      HttpResponse<byte[]> response = HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port
                      + "/api/console/config/tenant-package/excel/scenario-template?scenarios="
                      + scenario.getKey()))
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertThat(response.statusCode())
          .as("blank template download for %s", scenario.getKey())
          .isEqualTo(HttpStatus.OK.value());
      try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
        Sheet sheet = workbook.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET);
        assertThat(sheet).as("file template sheet for %s", scenario.getKey()).isNotNull();
        List<String> headers = new ArrayList<>();
        Row header = sheet.getRow(0);
        for (int column = 0; column < header.getLastCellNum(); column++) {
          headers.add(header.getCell(column).getStringCellValue());
        }
        assertThat(headers).containsAll(scenario.getValue());
        assertThat(headers)
            .as("projected columns for %s", scenario.getKey())
            .doesNotContainAnyElementsOf(excludedColumns.get(scenario.getKey()));

        int formatColumn = headers.indexOf("file_format_type");
        DataValidation formatValidation = sheet.getDataValidations().stream()
            .filter(validation ->
                validation.getRegions().getCellRangeAddress(0).getFirstColumn() == formatColumn)
            .findFirst()
            .orElseThrow();
        assertThat(formatValidation.getValidationConstraint().getExplicitListValues())
            .containsExactly(scenario.getKey().substring(scenario.getKey().indexOf('_') + 1));

        int typeColumn = headers.indexOf("template_type");
        DataValidation typeValidation = sheet.getDataValidations().stream()
            .filter(validation ->
                validation.getRegions().getCellRangeAddress(0).getFirstColumn() == typeColumn)
            .findFirst()
            .orElseThrow();
        assertThat(typeValidation.getValidationConstraint().getExplicitListValues())
            .containsExactly(scenario.getKey().startsWith("IMPORT") ? "IMPORT" : "EXPORT");
      }
    }
  }

  @Test
  @DisplayName("租户管理员可通过真实 HTTP 链路应用不含平台资源的业务场景包")
  void shouldAllowTenantAdminToApplyBusinessScenarioPackage() throws Exception {
    HttpResponse<String> response = applyWorkbookAsRole(
        importScenarioWorkbook(false),
        "tenant-admin-business-apply",
        "ROLE_TENANT_ADMIN",
        "tenant-admin-apply");

    assertThat(response.statusCode())
        .as("apply response: %s", response.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(new ObjectMapper().readTree(response.body()).path("code").asText())
        .isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("配置包中段写入失败时整包事务回滚前序实体")
  void shouldRollbackAllConfigurationWritesWhenApplyStepFails() throws Exception {
    String tenantId = "config-apply-rollback-it";
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String functionName = "test_fail_config_apply_" + suffix;
    String triggerName = "test_fail_config_apply_" + suffix;
    jdbcTemplate.execute("CREATE FUNCTION public." + functionName + "() RETURNS trigger "
        + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id = '" + tenantId + "' THEN "
        + "RAISE EXCEPTION 'intentional config apply rollback test'; END IF; "
        + "RETURN NEW; END $$");
    jdbcTemplate.execute("CREATE TRIGGER " + triggerName
        + " BEFORE INSERT OR UPDATE ON batch.job_definition "
        + "FOR EACH ROW EXECUTE FUNCTION public." + functionName + "()");

    try {
      HttpResponse<String> response = applyWorkbookAsRole(
          workbookForTenant(importScenarioWorkbook(false), tenantId),
          "apply-failure-" + suffix,
          "ROLE_ADMIN",
          tenantId);

      assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.job_definition where tenant_id = ?",
              Integer.class,
              tenantId))
          .isZero();
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.file_template_config where tenant_id = ?",
              Integer.class,
              tenantId))
          .isZero();
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.file_channel_config where tenant_id = ?",
              Integer.class,
              tenantId))
          .isZero();
    } finally {
      jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON batch.job_definition");
      jdbcTemplate.execute("DROP FUNCTION IF EXISTS public." + functionName + "()");
    }
  }

  @Test
  @DisplayName("调度场景精简监控列更新业务 SLA 且保留平台运行阈值")
  void shouldPreservePlatformMonitoringValues_whenApplyingScheduleScenarioSheet() throws Exception {
    String tenantId = "excel-upload-ta";
    String jobCode = "JOB_IMPORT_CUSTOMER";
    applyWorkbook(importScenarioWorkbook(false), "schedule-policy-seed");
    jdbcTemplate.update(
        "update batch.job_definition set schedule_type = 'CRON', schedule_expr = '0 0 2 * * ?' "
            + "where tenant_id = ? and job_code = ?",
        tenantId,
        jobCode);
    jdbcTemplate.update("""
        update batch.job_monitoring_policy
        set soft_runtime_seconds = 2400, soft_runtime_severity = 'ERROR',
            start_grace_seconds = 600, start_grace_severity = 'ERROR'
        where tenant_id = ? and job_definition_id =
            (select id from batch.job_definition where tenant_id = ? and job_code = ?)
        """, tenantId, tenantId, jobCode);

    HttpResponse<String> response = applyWorkbookAsRole(
        schedulePolicyWorkbook(), "schedule-policy-sparse-merge", "ROLE_TENANT_ADMIN", tenantId);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    Map<String, Object> policy = jdbcTemplate.queryForMap(
        "select soft_runtime_seconds, soft_runtime_severity, start_grace_seconds, "
            + "start_grace_severity, completion_deadline_local_time, "
            + "completion_deadline_day_offset from batch.job_monitoring_policy mp "
            + "join batch.job_definition jd on jd.id = mp.job_definition_id "
            + "where mp.tenant_id = ? and jd.job_code = ?",
        tenantId,
        jobCode);
    assertThat(policy)
        .containsEntry("soft_runtime_seconds", 2400)
        .containsEntry("soft_runtime_severity", "ERROR")
        .containsEntry("start_grace_seconds", 600)
        .containsEntry("start_grace_severity", "ERROR");
    assertThat(((Number) policy.get("completion_deadline_day_offset")).intValue())
        .isEqualTo(1);
    assertThat(policy.get("completion_deadline_local_time").toString()).isEqualTo("04:00:00");
  }

  @Test
  @DisplayName("旧配置包省略监控策略 Sheet 时不清除现有策略")
  void shouldPreserveMonitoringPolicyWhenLegacyWorkbookOmitsSheet() throws Exception {
    String tenantId = "legacy-monitoring-policy-it";
    String jobCode = "JOB_IMPORT_CUSTOMER";
    applyWorkbookAsRole(
        workbookForTenant(importScenarioWorkbook(false), tenantId),
        "legacy-monitoring-policy-seed",
        "ROLE_ADMIN",
        tenantId);
    jdbcTemplate.update(
        "update batch.job_definition set schedule_type = 'CRON', schedule_expr = '0 0 2 * * ?' "
            + "where tenant_id = ? and job_code = ?",
        tenantId,
        jobCode);
    jdbcTemplate.update("""
        update batch.job_monitoring_policy
        set soft_runtime_seconds = 2400, soft_runtime_severity = 'ERROR',
            start_grace_seconds = 600, start_grace_severity = 'ERROR',
            completion_deadline_local_time = '04:00', completion_deadline_day_offset = 1
        where tenant_id = ? and job_definition_id =
            (select id from batch.job_definition where tenant_id = ? and job_code = ?)
        """, tenantId, tenantId, jobCode);

    HttpResponse<String> response = applyWorkbookAsRole(
        jobOnlyWorkbookWithoutMonitoringPolicy(),
        "legacy-monitoring-policy-omitted",
        "ROLE_TENANT_ADMIN",
        tenantId);

    assertThat(response.statusCode())
        .as("apply response: %s", response.body())
        .isEqualTo(HttpStatus.OK.value());
    Map<String, Object> policy = jdbcTemplate.queryForMap(
        "select soft_runtime_seconds, soft_runtime_severity, start_grace_seconds, "
            + "start_grace_severity, completion_deadline_local_time, "
            + "completion_deadline_day_offset from batch.job_monitoring_policy mp "
            + "join batch.job_definition jd on jd.id = mp.job_definition_id "
            + "where mp.tenant_id = ? and jd.job_code = ?",
        tenantId,
        jobCode);
    assertThat(policy)
        .containsEntry("soft_runtime_seconds", 2400)
        .containsEntry("soft_runtime_severity", "ERROR")
        .containsEntry("start_grace_seconds", 600)
        .containsEntry("start_grace_severity", "ERROR")
        .containsEntry("completion_deadline_day_offset", 1);
    assertThat(policy.get("completion_deadline_local_time").toString()).isEqualTo("04:00:00");
  }

  @Test
  @DisplayName("预览后租户配置被其他入口修改时拒绝整包 Apply")
  void shouldRejectApplyWhenTenantConfigurationChangesAfterPreview() throws Exception {
    String tenantId = "config-version-conflict-it";
    String keySuffix = "config-version-conflict";
    String boundary = "excel-upload-" + keySuffix;
    byte[] workbook = workbookForTenant(tenantPackageWorkbook(false), tenantId);
    HttpRequest uploadRequest = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + keySuffix)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", "ROLE_ADMIN")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbook, tenantId)))
        .build();
    HttpResponse<String> uploadResponse =
        HttpClient.newHttpClient().send(uploadRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(uploadResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    String uploadToken = new ObjectMapper()
        .readTree(uploadResponse.body())
        .path("data")
        .path("uploadToken")
        .asText();
    HttpRequest previewRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/preview/" + uploadToken))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", "ROLE_ADMIN")
        .GET()
        .build();
    HttpResponse<String> previewResponse =
        HttpClient.newHttpClient().send(previewRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(previewResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    String previewToken = new ObjectMapper()
        .readTree(previewResponse.body())
        .path("data")
        .path("previewToken")
        .asText();

    jdbcTemplate.update(
        "insert into batch.resource_queue "
            + "(tenant_id, queue_code, queue_name, queue_type) values (?, ?, ?, 'IMPORT')",
        tenantId,
        "CONCURRENT_QUEUE",
        "Concurrent config write");

    HttpRequest applyRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/apply/" + uploadToken))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-apply-" + keySuffix)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", "ROLE_ADMIN")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"previewToken\":\"" + previewToken + "\"}", StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> applyResponse =
        HttpClient.newHttpClient().send(applyRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(applyResponse.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(new ObjectMapper().readTree(applyResponse.body()).path("code").asText())
        .isEqualTo("STATE_CONFLICT");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.file_channel_config where tenant_id = ? "
                + "and channel_code = 'excel_upload_local'",
            Integer.class,
            tenantId))
        .isZero();
  }

  @Test
  @DisplayName("配置版本函数只允许应用数据库角色执行")
  void shouldRestrictConfigurationRevisionFunctionsToApplicationRole() {
    Integer publicExecuteGrants = jdbcTemplate.queryForObject(
        "select count(*) from pg_proc p "
            + "cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
            + "where p.oid in ("
            + "'batch.current_tenant_configuration_revision(text)'::regprocedure, "
            + "'batch.lock_tenant_configuration_revision(text)'::regprocedure) "
            + "and acl.grantee = 0 and acl.privilege_type = 'EXECUTE'",
        Integer.class);
    Boolean applicationCanReadRevision = jdbcTemplate.queryForObject(
        "select has_function_privilege(current_user, "
            + "'batch.current_tenant_configuration_revision(text)', 'EXECUTE')",
        Boolean.class);
    Boolean applicationCanLockRevision = jdbcTemplate.queryForObject(
        "select has_function_privilege(current_user, "
            + "'batch.lock_tenant_configuration_revision(text)', 'EXECUTE')",
        Boolean.class);

    assertThat(publicExecuteGrants).isZero();
    assertThat(applicationCanReadRevision).isTrue();
    assertThat(applicationCanLockRevision).isTrue();
  }

  @Test
  @DisplayName("MERGE 更新禁用日历并保留日历和窗口的空白可选字段")
  void shouldMergeCalendarAndWindowWithoutResettingBlankOptionalFields() throws Exception {
    String tenantId = "excel-upload-ta";
    HttpResponse<String> initialPreview = previewWorkbookAsRole(
        calendarAndWindowWorkbook(false),
        "calendar-window-merge-initial-preview",
        "ROLE_TENANT_ADMIN",
        tenantId);
    assertThat(initialPreview.statusCode())
        .as("initial preview: %s", initialPreview.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(new ObjectMapper()
            .readTree(initialPreview.body())
            .path("data")
            .path("invalidRows")
            .asInt())
        .as("initial validation issues: %s", initialPreview.body())
        .isZero();
    HttpResponse<String> initialApply = applyWorkbookAsRole(
        calendarAndWindowWorkbook(false),
        "calendar-window-merge-initial",
        "ROLE_TENANT_ADMIN",
        tenantId);
    assertThat(initialApply.statusCode())
        .as("initial apply: %s", initialApply.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(initialApply.body()).contains("\"businessCalendarInserted\":1");

    HttpResponse<String> sparsePreview = previewWorkbookAsRole(
        calendarAndWindowWorkbook(true),
        "calendar-window-merge-sparse-preview",
        "ROLE_TENANT_ADMIN",
        tenantId);
    assertThat(sparsePreview.statusCode())
        .as("sparse preview: %s", sparsePreview.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(new ObjectMapper()
            .readTree(sparsePreview.body())
            .path("data")
            .path("invalidRows")
            .asInt())
        .as("sparse validation issues: %s", sparsePreview.body())
        .isZero();

    HttpResponse<String> response = applyWorkbookAsRole(
        calendarAndWindowWorkbook(true),
        "calendar-window-merge-sparse",
        "ROLE_TENANT_ADMIN",
        tenantId);

    assertThat(response.statusCode())
        .as("apply response: %s", response.body())
        .isEqualTo(HttpStatus.OK.value());
    Map<String, Object> calendar = jdbcTemplate.queryForMap(
        "select calendar_name, timezone, holiday_roll_rule, catch_up_policy, "
            + "catch_up_max_days, enabled, description from batch.business_calendar "
            + "where tenant_id = ? and calendar_code = ?",
        tenantId,
        "cal_merge");
    assertThat(calendar)
        .containsEntry("calendar_name", "Updated calendar")
        .containsEntry("timezone", "America/New_York")
        .containsEntry("holiday_roll_rule", "NEXT_WORKDAY")
        .containsEntry("catch_up_policy", "AUTO")
        .containsEntry("catch_up_max_days", 5)
        .containsEntry("enabled", false)
        .containsEntry("description", "Keep calendar settings");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.calendar_holiday h "
                + "join batch.business_calendar c on c.id = h.calendar_id "
                + "where c.tenant_id = ? and c.calendar_code = ? and h.day_type = 'HOLIDAY'",
            Integer.class,
            tenantId,
            "cal_merge"))
        .isEqualTo(2);

    Map<String, Object> window = jdbcTemplate.queryForMap(
        "select window_name, timezone, start_time::text, end_time::text, end_strategy, "
            + "out_of_window_action, allow_cross_day, enabled, description "
            + "from batch.batch_window where tenant_id = ? and window_code = ?",
        tenantId,
        "window_merge");
    assertThat(window)
        .containsEntry("window_name", "Updated window")
        .containsEntry("timezone", "America/New_York")
        .containsEntry("start_time", "02:00:00")
        .containsEntry("end_time", "22:00:00")
        .containsEntry("end_strategy", "STOP")
        .containsEntry("out_of_window_action", "FAIL")
        .containsEntry("allow_cross_day", true)
        .containsEntry("enabled", false)
        .containsEntry("description", "Keep window settings");
  }

  @Test
  @DisplayName("租户管理员应用含资源队列的旧全量包时服务端拒绝且不静默忽略")
  void shouldRejectTenantAdminApplyingLegacyPackageWithPlatformResources() throws Exception {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer =
        new ConfigPackageExcelWorkbookWriter(messages, List.of("ALL"));
    byte[] workbook =
        writer.buildExportWorkbook(ConfigPackageSampleDataFactory.sampleSheets("ALL"));

    HttpResponse<String> response = applyWorkbookAsRole(
        workbook,
        "tenant-admin-platform-resource-rejected",
        "ROLE_TENANT_ADMIN",
        "tenant-admin-apply");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(new ObjectMapper().readTree(response.body()).path("code").asText())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  @DisplayName("MERGE 保留作业、文件模板和 Pipeline 的空白选填值且不删除省略 steps")
  void shouldPreserveExistingValuesWhenOptionalFieldsAndPipelineStepsAreOmitted() throws Exception {
    byte[] initialWorkbook = importScenarioWorkbook(false);
    applyWorkbook(initialWorkbook, "merge-initial");

    String jobCode = "JOB_IMPORT_CUSTOMER";
    String tenantId = "excel-upload-ta";
    String originalDescription = jdbcTemplate.queryForObject(
        "select description from batch.job_definition where tenant_id = ? and job_code = ?",
        String.class,
        tenantId,
        jobCode);
    String originalParams = jdbcTemplate.queryForObject(
        "select default_params::text from batch.job_definition where tenant_id = ? and job_code = ?",
        String.class,
        tenantId,
        jobCode);
    List<Map<String, Object>> fileTemplateRows = jdbcTemplate.queryForList(
        "select tenant_id, template_code, version, description from batch.file_template_config "
            + "where template_code like 'tpl_import_%' order by tenant_id, template_code, version");
    assertThat(fileTemplateRows)
        .as("file templates persisted by the initial scenario import")
        .isNotEmpty();
    String templateDescription = jdbcTemplate.queryForObject(
        "select description from batch.file_template_config where tenant_id = ? and template_code = ?",
        String.class,
        tenantId,
        "tpl_import_customer");
    String pipelineDescription = jdbcTemplate.queryForObject(
        "select description from batch.pipeline_definition where tenant_id = ? and job_code = ?",
        String.class,
        tenantId,
        jobCode);

    byte[] mergeWorkbook = importScenarioWorkbook(true);
    applyWorkbook(mergeWorkbook, "merge-omitted-values");

    assertThat(jdbcTemplate.queryForObject(
            "select description from batch.job_definition where tenant_id = ? and job_code = ?",
            String.class,
            tenantId,
            jobCode))
        .isEqualTo(originalDescription);
    assertThat(jdbcTemplate.queryForObject(
            "select default_params::text from batch.job_definition where tenant_id = ? and job_code = ?",
            String.class,
            tenantId,
            jobCode))
        .isEqualTo(originalParams);
    assertThat(jdbcTemplate.queryForObject(
            "select description from batch.file_template_config where tenant_id = ? and template_code = ?",
            String.class,
            tenantId,
            "tpl_import_customer"))
        .isEqualTo(templateDescription);
    assertThat(jdbcTemplate.queryForObject(
            "select description from batch.pipeline_definition where tenant_id = ? and job_code = ?",
            String.class,
            tenantId,
            jobCode))
        .isEqualTo(pipelineDescription);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.pipeline_definition pd "
                + "join batch.pipeline_step_definition ps on ps.pipeline_definition_id = pd.id "
                + "where pd.tenant_id = ? and pd.job_code = ?",
            Integer.class,
            tenantId,
            jobCode))
        .isEqualTo(6);
  }

  @Test
  @DisplayName("Workflow 配置包 MERGE 保留可空字段且不删除省略的节点和边")
  void shouldPreserveWorkflowValuesAndOmittedNodesAndEdgesDuringMerge() throws Exception {
    byte[] initialWorkbook = workflowScenarioWorkbook(false);
    HttpResponse<String> initialPreview = previewWorkbookAsRole(
        initialWorkbook, "workflow-merge-initial-preview", "ROLE_ADMIN", "excel-upload-ta");
    assertThat(initialPreview.body())
        .as("workflow configuration validation preview")
        .contains("\"invalidRows\":0")
        .contains("\"issues\":[]");
    assertSuccessfulApplyAsPlatformAdmin(initialWorkbook, "workflow-merge-initial");

    String tenantId = "excel-upload-ta";
    String workflowCode = "WF_CUSTOMER_EOD";
    String originalDescription = jdbcTemplate.queryForObject(
        "select description from batch.workflow_definition "
            + "where tenant_id = ? and workflow_code = ? and version = 1",
        String.class,
        tenantId,
        workflowCode);
    String originalNodeName = jdbcTemplate.queryForObject(
        "select node_name from batch.workflow_node wn "
            + "join batch.workflow_definition wd on wd.id = wn.workflow_definition_id "
            + "where wd.tenant_id = ? and wd.workflow_code = ? and wd.version = 1 "
            + "and wn.node_code = 'START'",
        String.class,
        tenantId,
        workflowCode);

    byte[] sparseWorkbook = workflowScenarioWorkbook(true);
    HttpResponse<String> sparsePreview = previewWorkbookAsRole(
        sparseWorkbook, "workflow-merge-sparse-preview", "ROLE_ADMIN", tenantId);
    assertThat(sparsePreview.body())
        .as("sparse workflow configuration validation preview")
        .contains("\"invalidRows\":0")
        .contains("\"issues\":[]");
    assertSuccessfulApplyAsPlatformAdmin(sparseWorkbook, "workflow-merge-sparse");

    assertThat(jdbcTemplate.queryForObject(
            "select description from batch.workflow_definition "
                + "where tenant_id = ? and workflow_code = ? and version = 1",
            String.class,
            tenantId,
            workflowCode))
        .isEqualTo(originalDescription);
    assertThat(jdbcTemplate.queryForObject(
            "select node_name from batch.workflow_node wn "
                + "join batch.workflow_definition wd on wd.id = wn.workflow_definition_id "
                + "where wd.tenant_id = ? and wd.workflow_code = ? and wd.version = 1 "
                + "and wn.node_code = 'START'",
            String.class,
            tenantId,
            workflowCode))
        .isEqualTo(originalNodeName);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node wn "
                + "join batch.workflow_definition wd on wd.id = wn.workflow_definition_id "
                + "where wd.tenant_id = ? and wd.workflow_code = ? and wd.version = 1",
            Integer.class,
            tenantId,
            workflowCode))
        .isEqualTo(4);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_edge we "
                + "join batch.workflow_definition wd on wd.id = we.workflow_definition_id "
                + "where wd.tenant_id = ? and wd.workflow_code = ? and wd.version = 1",
            Integer.class,
            tenantId,
            workflowCode))
        .isEqualTo(4);
  }

  private static byte[] workflowScenarioWorkbook(boolean sparseUpdate) throws Exception {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer =
        new ConfigPackageExcelWorkbookWriter(messages, List.of("WORKFLOW"));
    List<List<Map<String, Object>>> sheets =
        ConfigPackageSampleDataFactory.sampleSheets("WORKFLOW");
    for (int index = 0; index < 9; index++) {
      sheets.get(index).clear();
    }
    if (sparseUpdate) {
      sheets.get(10).removeIf(row -> !List.of("START", "END").contains(row.get("node_code")));
    }
    if (sparseUpdate) {
      sheets.get(11).clear();
      sheets
          .get(11)
          .add(new LinkedHashMap<>(Map.of(
              "workflow_code", "WF_CUSTOMER_EOD",
              "workflow_version", "1",
              "from_node_code", "START",
              "to_node_code", "END",
              "edge_type", "SUCCESS",
              "condition_expr", "",
              "enabled", "true")));
    }
    byte[] workbookBytes = writer.buildExportWorkbook(sheets);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet definitions = workbook.getSheet(ConfigPackageExcelValidator.WF_DEF_SHEET);
      Row definitionHeader = definitions.getRow(0);
      Row definitionData = definitions.getRow(1);
      Sheet nodes = workbook.getSheet(ConfigPackageExcelValidator.WF_NODE_SHEET);
      Row nodeHeader = nodes.getRow(0);
      int nodeTypeColumn = columnIndex(nodeHeader, "node_type");
      int relatedJobColumn = columnIndex(nodeHeader, "related_job_code");
      for (int rowIndex = 1; rowIndex <= nodes.getLastRowNum(); rowIndex++) {
        Row node = nodes.getRow(rowIndex);
        if (node == null || node.getCell(columnIndex(nodeHeader, "node_code")) == null) {
          continue;
        }
        String nodeCode = node.getCell(columnIndex(nodeHeader, "node_code")).getStringCellValue();
        if ("NODE_IMPORT".equals(nodeCode) || "NODE_EXPORT".equals(nodeCode)) {
          node.getCell(nodeTypeColumn).setCellValue("TASK");
          node.getCell(relatedJobColumn).setBlank();
        }
      }
      Row startNode = rowForValue(nodes, columnIndex(nodeHeader, "node_code"), "START");
      if (sparseUpdate) {
        definitionData.getCell(columnIndex(definitionHeader, "description")).setBlank();
      } else {
        definitionData
            .getCell(columnIndex(definitionHeader, "description"))
            .setCellValue("Workflow MERGE description");
        startNode
            .getCell(columnIndex(nodeHeader, "node_name"))
            .setCellValue("Workflow MERGE start node");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private byte[] importScenarioWorkbook(boolean omitOptionalValuesAndSteps) throws Exception {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer =
        new ConfigPackageExcelWorkbookWriter(messages, List.of("IMPORT"));
    byte[] workbookBytes =
        writer.buildExportWorkbook(ConfigPackageSampleDataFactory.sampleSheets("IMPORT"));
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
      Sheet jobs = workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      Row jobHeader = jobs.getRow(0);
      Row jobData = jobs.getRow(1);
      Sheet fileTemplates = workbook.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET);
      Row fileTemplateHeader = fileTemplates.getRow(0);
      Row fileTemplateData = fileTemplates.getRow(1);
      Sheet pipelines = workbook.getSheet(ConfigPackageExcelValidator.PIPELINE_SHEET);
      Row pipelineHeader = pipelines.getRow(0);
      Row pipelineData = pipelines.getRow(1);
      if (omitOptionalValuesAndSteps) {
        jobData.getCell(columnIndex(jobHeader, "description")).setBlank();
        jobData.getCell(columnIndex(jobHeader, "default_params")).setBlank();
        fileTemplateData.getCell(columnIndex(fileTemplateHeader, "description")).setBlank();
        pipelineData.getCell(columnIndex(pipelineHeader, "description")).setBlank();
        Sheet steps = workbook.getSheet(ConfigPackageExcelValidator.STEP_SHEET);
        for (int rowIndex = steps.getLastRowNum(); rowIndex >= 1; rowIndex--) {
          Row row = steps.getRow(rowIndex);
          if (row != null) {
            steps.removeRow(row);
          }
        }
      } else {
        jobData
            .getCell(columnIndex(jobHeader, "description"))
            .setCellValue("MERGE value that must survive a blank update");
        fileTemplateData
            .getCell(columnIndex(fileTemplateHeader, "description"))
            .setCellValue("MERGE file template description");
        pipelineData
            .getCell(columnIndex(pipelineHeader, "description"))
            .setCellValue("MERGE pipeline description");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] schedulePolicyWorkbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      sheet(
          workbook,
          ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET,
          List.of(
              "job_code",
              "completion_deadline_local_time",
              "completion_deadline_day_offset",
              "dependency_completion_window_seconds",
              "completion_deadline_severity"),
          List.of(row(
              "job_code", "JOB_IMPORT_CUSTOMER",
              "completion_deadline_local_time", "04:00",
              "completion_deadline_day_offset", "1",
              "dependency_completion_window_seconds", "0",
              "completion_deadline_severity", "WARN")));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] jobOnlyWorkbookWithoutMonitoringPolicy() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      sheet(
          workbook,
          ConfigPackageExcelValidator.JOB_SHEET,
          ConfigPackageExcelSchema.JobDefinition.COLUMNS,
          List.of());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] workbookForTenant(byte[] source, String tenantId) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        Row header = sheet.getRow(0);
        if (header == null) {
          continue;
        }
        int tenantColumn = -1;
        for (int cellIndex = 0; cellIndex < header.getLastCellNum(); cellIndex++) {
          if (header.getCell(cellIndex) != null
              && "tenant_id".equals(header.getCell(cellIndex).getStringCellValue())) {
            tenantColumn = cellIndex;
            break;
          }
        }
        if (tenantColumn < 0) {
          continue;
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
          Row row = sheet.getRow(rowIndex);
          if (row != null && row.getCell(tenantColumn) != null) {
            row.getCell(tenantColumn).setCellValue(tenantId);
          }
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] calendarAndWindowWorkbook(boolean sparseUpdate) throws Exception {
    byte[] base = tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base))) {
      Map<String, String> calendar = row(
          "tenant_id", "excel-upload-ta",
          "calendar_code", "CAL_MERGE",
          "calendar_name", sparseUpdate ? "Updated calendar" : "Initial calendar",
          "timezone", "America/New_York",
          "holidays", sparseUpdate ? "2026-02-02" : "2026-01-01",
          "holiday_roll_rule", sparseUpdate ? "" : "NEXT_WORKDAY",
          "catch_up_policy", sparseUpdate ? "" : "AUTO",
          "catch_up_max_days", sparseUpdate ? "" : "5",
          "enabled", sparseUpdate ? "" : "false",
          "description", sparseUpdate ? "" : "Keep calendar settings");
      appendDataRow(
          workbook.getSheet(ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET),
          ConfigPackageExcelSchema.BusinessCalendar.COLUMNS,
          calendar);

      Map<String, String> window = row(
          "tenant_id", "excel-upload-ta",
          "window_code", "WINDOW_MERGE",
          "window_name", sparseUpdate ? "Updated window" : "Initial window",
          "timezone", "America/New_York",
          "start_time", sparseUpdate ? "02:00" : "01:00",
          "end_time", sparseUpdate ? "22:00" : "21:00",
          "end_strategy", sparseUpdate ? "" : "STOP",
          "out_of_window_action", sparseUpdate ? "" : "FAIL",
          "allow_cross_day", sparseUpdate ? "" : "true",
          "enabled", sparseUpdate ? "" : "false",
          "description", sparseUpdate ? "" : "Keep window settings");
      appendDataRow(
          workbook.getSheet(ConfigPackageExcelValidator.BATCH_WINDOW_SHEET),
          ConfigPackageExcelSchema.BatchWindow.COLUMNS,
          window);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private static void appendDataRow(Sheet sheet, List<String> columns, Map<String, String> values) {
    Row row = sheet.createRow(sheet.getLastRowNum() + 1);
    for (int index = 0; index < columns.size(); index++) {
      row.createCell(index).setCellValue(values.getOrDefault(columns.get(index), ""));
    }
  }

  private static int columnIndex(Row header, String columnName) {
    for (int cellIndex = 0; cellIndex < header.getLastCellNum(); cellIndex++) {
      if (columnName.equals(header.getCell(cellIndex).getStringCellValue())) {
        return cellIndex;
      }
    }
    throw new IllegalArgumentException("Missing workbook column: " + columnName);
  }

  private static void assertHeaderColumns(Sheet sheet, Set<String> expectedColumns) {
    Row header = sheet.getRow(0);
    assertThat(header).as("header row for %s", sheet.getSheetName()).isNotNull();
    Set<String> actualColumns = new LinkedHashSet<>();
    for (int index = 0; index < header.getLastCellNum(); index++) {
      actualColumns.add(header.getCell(index).getStringCellValue());
    }
    assertThat(actualColumns)
        .as("columns for %s", sheet.getSheetName())
        .containsAll(expectedColumns);
  }

  private static Row rowForValue(Sheet sheet, int columnIndex, String value) {
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row != null && value.equals(row.getCell(columnIndex).getStringCellValue())) {
        return row;
      }
    }
    throw new IllegalArgumentException("Missing workbook row for: " + value);
  }

  private void applyWorkbook(byte[] workbook, String keySuffix) throws Exception {
    HttpResponse<String> applyResponse = applyWorkbookAsRole(workbook, keySuffix, null);
    assertThat(applyResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(new ObjectMapper().readTree(applyResponse.body()).path("code").asText())
        .isEqualTo("SUCCESS");
  }

  private void assertSuccessfulApplyAsPlatformAdmin(byte[] workbook, String keySuffix)
      throws Exception {
    HttpResponse<String> response = applyWorkbookAsRole(workbook, keySuffix, "ROLE_ADMIN");
    assertThat(response.statusCode())
        .as("configuration apply response: %s", response.body())
        .isEqualTo(HttpStatus.OK.value());
    assertThat(new ObjectMapper().readTree(response.body()).path("code").asText())
        .isEqualTo("SUCCESS");
  }

  private HttpResponse<String> applyWorkbookAsRole(byte[] workbook, String keySuffix, String role)
      throws Exception {
    return applyWorkbookAsRole(workbook, keySuffix, role, "excel-upload-ta");
  }

  private HttpResponse<String> applyWorkbookAsRole(
      byte[] workbook, String keySuffix, String role, String tenantId) throws Exception {
    String boundary = "excel-upload-" + keySuffix;
    HttpRequest.Builder uploadRequest = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + keySuffix)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbook, tenantId)));
    if (role != null) {
      uploadRequest.header("X-Console-Roles", role);
    }
    HttpResponse<String> uploadResponse = HttpClient.newHttpClient()
        .send(uploadRequest.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(uploadResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    JsonNode upload = new ObjectMapper().readTree(uploadResponse.body());
    String token = upload.path("data").path("uploadToken").asText();
    assertThat(token).isNotBlank();

    HttpRequest.Builder previewRequest = HttpRequest.newBuilder(URI.create("http://localhost:"
            + port + "/api/console/config/tenant-package/excel/preview/" + token))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .GET();
    if (role != null) {
      previewRequest.header("X-Console-Roles", role);
    }
    HttpResponse<String> previewResponse = HttpClient.newHttpClient()
        .send(previewRequest.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(previewResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
    String previewToken = new ObjectMapper()
        .readTree(previewResponse.body())
        .path("data")
        .path("previewToken")
        .asText();
    assertThat(previewToken).isNotBlank();

    HttpRequest.Builder applyRequest = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/apply/" + token))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-apply-" + keySuffix)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"previewToken\":\"" + previewToken + "\"}", StandardCharsets.UTF_8));
    if (role != null) {
      applyRequest.header("X-Console-Roles", role);
    }
    return HttpClient.newHttpClient()
        .send(applyRequest.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postApply(
      String uploadToken, String previewToken, String idempotencyKey) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/console/config/tenant-package/excel/apply/" + uploadToken))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "excel-upload-ta")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"previewToken\":\"" + previewToken + "\"}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> previewWorkbookAsRole(
      byte[] workbook, String keySuffix, String role, String tenantId) throws Exception {
    String boundary = "excel-upload-" + keySuffix;
    HttpRequest uploadRequest = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/console/config/tenant-package/excel/upload"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + keySuffix)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", role)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, workbook, tenantId)))
        .build();
    HttpResponse<String> uploadResponse =
        HttpClient.newHttpClient().send(uploadRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(uploadResponse.statusCode())
        .as("upload response: %s", uploadResponse.body())
        .isEqualTo(HttpStatus.OK.value());
    String token = new ObjectMapper()
        .readTree(uploadResponse.body())
        .path("data")
        .path("uploadToken")
        .asText();
    HttpRequest previewRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/preview/" + token))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-Roles", role)
        .GET()
        .build();
    return HttpClient.newHttpClient().send(previewRequest, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> previewTokenAs(
      String token, String tenantId, String user, String role) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/console/config/tenant-package/excel/preview/" + token))
        .timeout(Duration.ofSeconds(60))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header("X-Console-User", user)
        .header("X-Console-Roles", role)
        .GET()
        .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] multipartBody(String boundary, byte[] workbook) throws Exception {
    return multipartBody(boundary, workbook, "excel-upload-ta");
  }

  private static byte[] withVbaProjectRelationship(byte[] workbookBytes) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(workbookBytes));
        ZipOutputStream zip = new ZipOutputStream(output)) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        byte[] content = input.readAllBytes();
        if (entry.getName().equals("xl/_rels/workbook.xml.rels")) {
          String relationships = new String(content, StandardCharsets.UTF_8);
          relationships = relationships.replace(
              "</Relationships>",
              "<Relationship Id=\"rIdVba\""
                  + " Type=\"http://schemas.microsoft.com/office/2006/relationships/vbaProject\""
                  + " Target=\"vbaProject.bin\"/></Relationships>");
          content = relationships.getBytes(StandardCharsets.UTF_8);
        } else if (entry.getName().equals("[Content_Types].xml")) {
          String contentTypes = new String(content, StandardCharsets.UTF_8);
          contentTypes = contentTypes.replace(
              "</Types>",
              "<Override PartName=\"/xl/vbaProject.bin\""
                  + " ContentType=\"application/vnd.ms-office.vbaProject\"/></Types>");
          content = contentTypes.getBytes(StandardCharsets.UTF_8);
        }
        zip.putNextEntry(new ZipEntry(entry.getName()));
        zip.write(content);
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
      zip.write(new byte[] {0x01, 0x02, 0x03});
      zip.closeEntry();
    }
    return output.toByteArray();
  }

  private static byte[] multipartBody(String boundary, byte[] workbook, String tenantId)
      throws Exception {
    return multipartBody(boundary, workbook, tenantId, "tenant-package.xlsx");
  }

  private static byte[] multipartBody(
      String boundary, byte[] workbook, String tenantId, String fileName) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writePart(out, boundary, "tenantId", null, null, tenantId.getBytes(StandardCharsets.UTF_8));
    writePart(out, boundary, "file", fileName, EXCEL_CONTENT_TYPE, workbook);
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static byte[] workbookWithOversizedSharedStringsEntry() throws Exception {
    byte[] baseWorkbook = tenantPackageWorkbook(true);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] chunk = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
    boolean replaced = false;
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(baseWorkbook));
        ZipOutputStream zip = new ZipOutputStream(output)) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if ("xl/sharedStrings.xml".equals(entry.getName())) {
          replaced = true;
          zip.putNextEntry(new ZipEntry(entry.getName()));
          zip.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              .concat("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
              .concat("<si><t>")
              .getBytes(StandardCharsets.UTF_8));
          for (int index = 0; index <= 1024; index++) {
            zip.write(chunk);
          }
          zip.write("</t></si></sst>".getBytes(StandardCharsets.UTF_8));
          zip.closeEntry();
        } else {
          zip.putNextEntry(new ZipEntry(entry.getName()));
          input.transferTo(zip);
          zip.closeEntry();
        }
        input.closeEntry();
      }
    }
    assertThat(replaced).isTrue();
    return output.toByteArray();
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

  static byte[] tenantPackageWorkbook(boolean includeMonitoringPolicySheet) {
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
      if (includeMonitoringPolicySheet) {
        sheet(
            workbook,
            ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET,
            ConfigPackageExcelSchema.JobMonitoringPolicy.COLUMNS,
            List.of());
      }
      sheet(
          workbook,
          ConfigPackageExcelValidator.CHANNEL_SHEET,
          ConfigPackageExcelSchema.FileChannel.COLUMNS,
          List.of(row(
              "tenant_id", "excel-upload-ta",
              "channel_code", "excel_upload_local",
              "channel_name", "Excel Upload Local",
              "channel_type", "LOCAL",
              "target_endpoint", "/tmp/excel-upload",
              "auth_type", "NONE",
              "config_json", "",
              "receipt_policy", "NONE",
              "timeout_seconds", "30",
              "enabled", "true")));
      sheet(
          workbook,
          ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
          ConfigPackageExcelSchema.FileTemplate.COLUMNS,
          List.of(row(
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
