package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.ai.enabled=false",
      "batch.console.security.rate-limit.expensive-op-user-limit-per-minute=100"
    })
@Import(ConsoleTenantConfigPackageAuthenticatedIdempotencyIntegrationTest.SecurityMode.class)
@DisplayName("租户配置包认证 Apply 幂等: 成功提交后同键重放不重复写入")
class ConsoleTenantConfigPackageAuthenticatedIdempotencyIntegrationTest
    extends AbstractIntegrationTest {

  private static final String TENANT_ID = "excel-upload-ta";

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireAuthentication() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof BatchSecurityProperties security) {
            security.setBypassMode(false);
          }
          return bean;
        }
      };
    }
  }

  @LocalServerPort
  private int port;

  @Autowired
  private ConsoleJwtService jwtService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("真实 JWT/CSRF 上传、预览、Apply 后同键重放返回 409 且业务行只写一次")
  void shouldPersistOnceWhenSuccessfulApplyIsReplayedWithSameKey() throws Exception {
    String suffix = Long.toUnsignedString(System.nanoTime(), 36);
    String channelCode = "idem_apply_" + suffix;
    String templateCode = "idem_template_" + suffix;
    String uploadToken = null;
    String jwt = jwtService
        .issueToken("config-idempotency-tenant-admin", TENANT_ID, Set.of("ROLE_TENANT_ADMIN"))
        .accessToken();
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();

    ResponseCookie csrfCookie = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();

    try {
      byte[] workbook = workbookWithUniqueCodes(channelCode, templateCode);
      MultipartBodyBuilder multipart = new MultipartBodyBuilder();
      multipart
          .part("file", new ByteArrayResource(workbook) {
            @Override
            public String getFilename() {
              return "tenant-config.xlsx";
            }
          })
          .contentType(MediaType.parseMediaType(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

      String uploadBody = client
          .post()
          .uri("/api/console/config/tenant-package/excel/upload")
          .cookie("batch_console_token", jwt)
          .cookie("XSRF-TOKEN", csrfCookie.getValue())
          .header("X-XSRF-TOKEN", csrfCookie.getValue())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-upload-" + suffix)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(BodyInserters.fromMultipartData(multipart.build()))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(uploadBody).isNotBlank();
      uploadToken = new ObjectMapper()
          .readTree(uploadBody)
          .path("data")
          .path("uploadToken")
          .asText();
      assertThat(uploadToken).isNotBlank();

      String previewBody = client
          .get()
          .uri("/api/console/config/tenant-package/excel/preview/{token}", uploadToken)
          .cookie("batch_console_token", jwt)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(previewBody).isNotBlank();
      String previewToken = new ObjectMapper()
          .readTree(previewBody)
          .path("data")
          .path("previewToken")
          .asText();
      assertThat(previewToken).isNotBlank();

      String applyUri = "/api/console/config/tenant-package/excel/apply/" + uploadToken;
      String idempotencyKey = "idem-apply-" + suffix;
      client
          .post()
          .uri(applyUri)
          .cookie("batch_console_token", jwt)
          .cookie("XSRF-TOKEN", csrfCookie.getValue())
          .header("X-XSRF-TOKEN", csrfCookie.getValue())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"previewToken\":\"" + previewToken + "\"}")
          .exchange()
          .expectStatus()
          .isOk();

      assertThat(channelCount(channelCode)).isEqualTo(1);

      client
          .post()
          .uri(applyUri)
          .cookie("batch_console_token", jwt)
          .cookie("XSRF-TOKEN", csrfCookie.getValue())
          .header("X-XSRF-TOKEN", csrfCookie.getValue())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"previewToken\":\"" + previewToken + "\"}")
          .exchange()
          .expectStatus()
          .isEqualTo(409);

      assertThat(channelCount(channelCode)).isEqualTo(1);
    } finally {
      if (uploadToken != null) {
        jdbcTemplate.update(
            "delete from batch.file_channel_config where tenant_id = ? and channel_code = ?",
            TENANT_ID,
            channelCode);
        jdbcTemplate.update(
            "delete from batch.file_template_config where tenant_id = ? and template_code = ?",
            TENANT_ID,
            templateCode);
      }
    }
  }

  private Integer channelCount(String channelCode) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.file_channel_config where tenant_id = ? and channel_code = ?",
        Integer.class,
        TENANT_ID,
        channelCode);
  }

  private static byte[] workbookWithUniqueCodes(String channelCode, String templateCode)
      throws Exception {
    byte[] source =
        ConsoleTenantConfigPackageExcelUploadIntegrationTest.tenantPackageWorkbook(true);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
      replaceWorkbookValue(workbook, "file_channel_config", "channel_code", channelCode);
      replaceWorkbookValue(workbook, "file_template_config", "template_code", templateCode);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static void replaceWorkbookValue(
      XSSFWorkbook workbook, String sheetName, String columnName, String value) {
    var sheet = workbook.getSheet(sheetName);
    Row header = sheet.getRow(0);
    for (int i = 0; i < header.getLastCellNum(); i++) {
      if (columnName.equals(header.getCell(i).getStringCellValue())) {
        sheet.getRow(1).getCell(i).setCellValue(value);
        return;
      }
    }
    throw new IllegalStateException(sheetName + " workbook is missing " + columnName);
  }
}
