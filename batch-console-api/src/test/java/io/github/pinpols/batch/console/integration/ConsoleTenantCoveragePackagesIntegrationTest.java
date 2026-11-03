package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePasswordHasher;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.security.cookie-secure=false",
      "batch.console.ai.enabled=false",
      "batch.console.read-replica.enabled=false",
      "batch.console.security.rate-limit.expensive-op-user-limit-per-minute=100"
    })
@Import(ConsoleTenantConfigPackageAuthenticatedIdempotencyIntegrationTest.SecurityMode.class)
@DisplayName("三租户覆盖包：隔离数据库真实认证上传、预览、应用和回读")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConsoleTenantCoveragePackagesIntegrationTest extends AbstractIntegrationTest {
  @LocalServerPort
  private int port;

  private final ConsoleJwtService jwtService;
  private final JdbcTemplate jdbcTemplate;
  private final ConsolePasswordHasher passwordHasher;

  ConsoleTenantCoveragePackagesIntegrationTest(
      ConsoleJwtService jwtService,
      JdbcTemplate jdbcTemplate,
      ConsolePasswordHasher passwordHasher) {
    this.jwtService = jwtService;
    this.jdbcTemplate = jdbcTemplate;
    this.passwordHasher = passwordHasher;
  }

  @BeforeEach
  void resetCoverageTenants() {
    jdbcTemplate.update(
        "delete from batch.console_user_account where tenant_id in ('coverage-admin', 'ta', 'tb', 'tc')");
    jdbcTemplate.update(
        "delete from batch.idempotency_record where tenant_id in ('coverage-admin', 'ta', 'tb', 'tc')");
    jdbcTemplate.update(
        "delete from batch.tenant where tenant_id in ('coverage-admin', 'ta', 'tb', 'tc')");
  }

  @Test
  @DisplayName("初始化脚本使用隔离 Console 创建并导入三个覆盖租户")
  void shouldInitializeCoverageTenants_whenUsingCli() throws Exception {
    String password = "Coverage-Test-Admin-8842!";
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values ('coverage-admin', 'coverage-admin', 'ACTIVE')");
    jdbcTemplate.update(
        "insert into batch.console_user_account (tenant_id, username, display_name, password_hash, authorities_csv, enabled) values (?, ?, ?, ?, ?, true)",
        "coverage-admin",
        "coverage-platform-admin",
        "coverage-platform-admin",
        passwordHasher.encode(password),
        "ROLE_ADMIN");
    Path temp = Files.createTempDirectory("coverage-cli-test-");
    Path receipt = temp.resolve("receipt.json");
    Path credentials = temp.resolve("credentials.json");
    try {
      Process process = new ProcessBuilder(
              "python3",
              "../scripts/data/init-coverage-tenants.py",
              "--base-url",
              "http://127.0.0.1:" + port,
              "--operator",
              "coverage-platform-admin",
              "--receipt",
              receipt.toString(),
              "--credentials-file",
              credentials.toString(),
              "--acknowledge-insert-only-limitation",
              "--apply")
          .redirectErrorStream(true)
          .start();
      process.getOutputStream().write((password + "\n").getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as("初始化脚本退出：%s", output).isZero();
      JsonNode states = new ObjectMapper().readTree(Files.readString(receipt)).path("tenants");
      for (String tenant : new String[] {"ta", "tb", "tc"}) {
        assertThat(states.path(tenant).path("stage").asText()).isEqualTo("applied");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from batch.job_definition where tenant_id = ?",
                Integer.class,
                tenant))
            .isEqualTo(9);
      }
    } finally {
      Files.deleteIfExists(credentials);
      Files.deleteIfExists(receipt);
      Files.deleteIfExists(temp.resolve("receipt.json.lock"));
      Files.deleteIfExists(temp);
    }
  }

  @Test
  @DisplayName("三个独立租户配置包逐个通过认证并写入对应租户")
  void shouldUploadPreviewAndApply_whenUsingEachCoveragePackage() throws Exception {
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(90))
        .build();
    client.get().uri("/api/console/auth/check").exchange().expectStatus().isUnauthorized();
    ObjectMapper mapper = new ObjectMapper();
    for (String tenant : new String[] {"ta", "tb", "tc"}) {
      jdbcTemplate.update(
          "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
          tenant,
          tenant);
      String jwt = jwtService
          .issueToken("coverage-" + tenant, tenant, Set.of("ROLE_ADMIN"))
          .accessToken();
      ResponseCookie csrf = client
          .get()
          .uri("/api/console/auth/check")
          .cookie("batch_console_token", jwt)
          .exchange()
          .expectStatus()
          .isNoContent()
          .returnResult(Void.class)
          .getResponseCookies()
          .getFirst("XSRF-TOKEN");
      assertThat(csrf).isNotNull();
      Path file = Path.of(
          "..",
          "test-data",
          "tenant-config-packages",
          "coverage-examples",
          tenant + "-coverage-example.xlsx");
      byte[] workbook = Files.readAllBytes(file);
      MultipartBodyBuilder multipart = new MultipartBodyBuilder();
      multipart
          .part("file", new ByteArrayResource(workbook) {
            @Override
            public String getFilename() {
              return tenant + "-coverage-example.xlsx";
            }
          })
          .contentType(MediaType.parseMediaType(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
      String upload = client
          .post()
          .uri("/api/console/config/tenant-package/excel/upload?tenantId=" + tenant)
          .cookie("batch_console_token", jwt)
          .cookie("XSRF-TOKEN", csrf.getValue())
          .header("X-XSRF-TOKEN", csrf.getValue())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenant)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "coverage-upload-" + tenant)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(BodyInserters.fromMultipartData(multipart.build()))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      String uploadToken =
          mapper.readTree(upload).path("data").path("uploadToken").asText();
      assertThat(uploadToken).as(tenant + " upload token").isNotBlank();
      String preview = client
          .get()
          .uri("/api/console/config/tenant-package/excel/preview/{token}", uploadToken)
          .cookie("batch_console_token", jwt)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenant)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode data = mapper.readTree(preview).path("data");
      assertThat(data.path("invalidRows").asInt())
          .as(tenant + " preview: " + preview)
          .isZero();
      String previewToken = data.path("previewToken").asText();
      assertThat(previewToken).isNotBlank();
      client
          .post()
          .uri("/api/console/config/tenant-package/excel/apply/{token}", uploadToken)
          .cookie("batch_console_token", jwt)
          .cookie("XSRF-TOKEN", csrf.getValue())
          .header("X-XSRF-TOKEN", csrf.getValue())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenant)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "coverage-apply-" + tenant)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"previewToken\":\"" + previewToken + "\"}")
          .exchange()
          .expectStatus()
          .isOk();
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.job_definition where tenant_id = ?",
              Integer.class,
              tenant))
          .isEqualTo(9);
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.workflow_definition where tenant_id = ? and single_job_definition_id is null",
              Integer.class,
              tenant))
          .isEqualTo(3);
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.workflow_node where tenant_id = ? and node_type = 'WAIT'",
              Integer.class,
              tenant))
          .isEqualTo(1);
      assertThat(jdbcTemplate.queryForList(
              "select job_type from batch.job_definition where tenant_id = ?",
              String.class,
              tenant))
          .containsExactlyInAnyOrder(
              "GENERAL",
              "IMPORT",
              "EXPORT",
              "PROCESS",
              "DISPATCH",
              "ATOMIC",
              "BUNDLE_IMPORT",
              "BUNDLE_EXPORT",
              "BUNDLE_DISPATCH");
      assertThat(jdbcTemplate.queryForObject(
              "select enabled from batch.job_definition where tenant_id = ? and job_type = 'GENERAL'",
              Boolean.class,
              tenant))
          .isFalse();
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.workflow_definition where tenant_id = ? and single_job_definition_id is null and schedule_enabled = true",
              Integer.class,
              tenant))
          .isZero();
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.workflow_definition where tenant_id = ? and schedule_type <> 'MANUAL'",
              Integer.class,
              tenant))
          .isZero();
      String expectedJoin = tenant.equals("ta") ? "ALL" : tenant.equals("tb") ? "ANY" : "N_OF";
      assertThat(jdbcTemplate.queryForObject(
              "select node_params ->> 'joinMode' from batch.workflow_node where tenant_id = ? and node_code = 'MERGE'",
              String.class,
              tenant))
          .isEqualTo(expectedJoin);
      assertThat(jdbcTemplate.queryForObject(
              "select condition_expr from batch.workflow_edge where tenant_id = ? and edge_type = 'CONDITION'",
              String.class,
              tenant))
          .isEqualTo("proceed == true");
      assertThat(jdbcTemplate.queryForObject(
              "select depends_on_job_code from batch.job_definition where tenant_id = ? and job_type = 'EXPORT'",
              String.class,
              tenant))
          .isEqualTo(tenant.toUpperCase() + "_COV_IMPORT");
    }
  }
}
