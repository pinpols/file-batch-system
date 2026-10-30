package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 真实 HTTP、版本事务和持久化审计共同验证密钥引用的存储身份；不读取或输出密钥载荷。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.startup-self-check.enabled=false")
@DisplayName("密钥引用身份：清洗后的存储引用贯穿版本分配、当前版本和审计")
class ConfigSecretReferenceIdentityIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;

  @LocalServerPort
  private int port;

  private WebTestClient client;

  @Autowired
  ConfigSecretReferenceIdentityIntegrationTest(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @BeforeEach
  void createClient() {
    client = WebTestClient.bindToServer(new JdkClientHttpConnector())
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
  }

  static Stream<Arguments> acceptedAliases() {
    return Stream.of(
        Arguments.of("token-ref", " token-ref "), Arguments.of("token-ref", "ｔｏｋｅｎ－ｒｅｆ"));
  }

  @ParameterizedTest
  @MethodSource("acceptedAliases")
  @DisplayName("合法空白和全角别名再次轮换时递增同一存储引用的版本并更新当前版本")
  void shouldRotateCanonicalSecretReferenceAliases(String canonicalRef, String suppliedRef) {
    String tenantId = "secret-identity-" + Long.toUnsignedString(System.nanoTime());
    rotate(tenantId, canonicalRef, "PUBLISHED", 1);

    rotate(tenantId, suppliedRef, "PUBLISHED", 2);

    String versionStateSql = """
        select secret_ref, version_no, current_version from batch.secret_version
        where tenant_id = ? order by version_no
        """;
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(versionStateSql, tenantId);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0))
        .containsEntry("secret_ref", canonicalRef)
        .containsEntry("version_no", 1)
        .containsEntry("current_version", false);
    assertThat(versions.get(1))
        .containsEntry("secret_ref", canonicalRef)
        .containsEntry("version_no", 2)
        .containsEntry("current_version", true);
    assertCanonicalAudit(tenantId, canonicalRef, 2);
  }

  @Test
  @DisplayName("别名草稿递增版本但保留已发布当前版本，随后灰度轮换继续同一序列")
  void shouldPreserveCurrentVersion_whenRotatingDraftAlias() {
    String tenantId = "secret-draft-" + Long.toUnsignedString(System.nanoTime());
    rotate(tenantId, "token-ref", "PUBLISHED", 1);
    rotate(tenantId, " token-ref ", "DRAFT", 2);

    assertCurrentVersion(tenantId, 1);
    String draftSql = """
        select secret_ref, secret_status, current_version from batch.secret_version
        where tenant_id = ? and version_no = 2
        """;
    assertThat(jdbcTemplate.queryForMap(draftSql, tenantId))
        .containsEntry("secret_ref", "token-ref")
        .containsEntry("secret_status", "DRAFT")
        .containsEntry("current_version", false);

    rotate(tenantId, "ｔｏｋｅｎ－ｒｅｆ", "GRAY", 3);
    assertCurrentVersion(tenantId, 3);
    assertCanonicalAudit(tenantId, "token-ref", 3);
  }

  @Test
  @DisplayName("标准引用连续轮换保持递增，别名轮换不降级其他租户的同名当前版本")
  void shouldPreserveTenantScope_whenRotatingReferenceAlias() {
    String tenantId = "secret-owner-" + Long.toUnsignedString(System.nanoTime());
    String otherTenantId = "secret-other-" + Long.toUnsignedString(System.nanoTime());
    rotate(tenantId, "token-ref", "PUBLISHED", 1);
    rotate(otherTenantId, "token-ref", "PUBLISHED", 1);
    rotate(tenantId, "token-ref", "PUBLISHED", 2);
    rotate(tenantId, " token-ref ", "PUBLISHED", 3);

    assertCurrentVersion(tenantId, 3);
    assertCurrentVersion(otherTenantId, 1);
    assertCanonicalAudit(tenantId, "token-ref", 3);
    assertCanonicalAudit(otherTenantId, "token-ref", 1);
  }

  private void assertCurrentVersion(String tenantId, int expectedVersion) {
    String currentVersionSql = """
        select version_no from batch.secret_version
        where tenant_id = ? and secret_ref = 'token-ref' and current_version = true
        """;
    assertThat(jdbcTemplate.queryForList(currentVersionSql, Integer.class, tenantId))
        .containsExactly(expectedVersion);
  }

  private void assertCanonicalAudit(String tenantId, String secretRef, int expectedChanges) {
    String auditSql = """
        select config_key, version_no from batch.config_change_log
        where tenant_id = ? and config_type = 'SECRET' and change_action = 'ROTATE'
        order by version_no
        """;
    List<Map<String, Object>> changes = jdbcTemplate.queryForList(auditSql, tenantId);
    assertThat(changes).hasSize(expectedChanges);
    assertThat(changes)
        .allSatisfy(change -> assertThat(change).containsEntry("config_key", secretRef));
    assertThat(changes)
        .extracting(change -> change.get("version_no"))
        .containsExactlyElementsOf(
            IntStream.rangeClosed(1, expectedChanges).boxed().toList());
  }

  private void rotate(String tenantId, String secretRef, String status, int expectedVersion) {
    String operationKey = "secret-identity-" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> request = Map.of(
        "tenantId", tenantId,
        "secretRef", secretRef,
        "secretName", "identity fixture",
        "secretPayloadJson", "{}",
        "secretStatus", status);
    client
        .post()
        .uri("/api/console/config/secrets/rotate")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data")
        .isEqualTo(expectedVersion);
  }
}
