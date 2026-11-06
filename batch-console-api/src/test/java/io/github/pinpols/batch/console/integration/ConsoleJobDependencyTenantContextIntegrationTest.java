package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * ARCH-20261103-01：Console 以不能绕过 RLS 的普通数据库角色访问作业依赖。
 *
 * <p>{@code batch.job_dependency} 强制 RLS，按 {@code app.tenant_id} 隔离；平台库 owner 账号是超级用户时 RLS 不生效，问题被掩盖。
 * 本测试让 Console 的每个平台库连接切换到普通角色，经真实 HTTP 验证作业依赖的写入、查询与环路校验都在已绑定租户的事务内执行。
 * 夹具与断言使用独立的 owner 连接，不受角色切换影响。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConsoleJobDependencyTenantContextIntegrationTest.ApplicationRoleConnections.class)
@DisplayName("作业依赖在普通数据库角色下的租户上下文：写入、查询与环路校验")
class ConsoleJobDependencyTenantContextIntegrationTest extends AbstractIntegrationTest {

  private static final String APPLICATION_ROLE = "job_dependency_app_test";
  private static final String TENANT = "int-ta";
  private static volatile boolean restricted;

  @LocalServerPort
  private int port;

  private WebTestClient client;
  private JdbcTemplate owner;

  @BeforeEach
  void prepareApplicationRole() {
    var postgres = AbstractIntegrationTest.PLATFORM_POSTGRES;
    owner = new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    owner.execute("""
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'job_dependency_app_test') THEN
            CREATE ROLE job_dependency_app_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """);
    for (String schema : new String[] {"batch", "archive", "quartz"}) {
      owner.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + APPLICATION_ROLE);
      owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema
          + " TO " + APPLICATION_ROLE);
      owner.execute(
          "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + APPLICATION_ROLE);
      owner.execute(
          "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " + schema + " TO " + APPLICATION_ROLE);
    }
    TestTenantCalendarFixtures.ensureDefaultCalendar(owner, TENANT);
    client = WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
    restricted = true;
  }

  @AfterEach
  void restoreOwnerRole() {
    restricted = false;
  }

  @Test
  @DisplayName("创建与修改依赖作业时，触发器同步的依赖行通过 RLS 写入并可在详情和列表中读回")
  void shouldWriteAndReadDependencies_whenApplicationRoleCannotBypassRls() {
    String suffix = suffix();
    String upstreamOne = "DEP_UP1_" + suffix;
    String upstreamTwo = "DEP_UP2_" + suffix;
    String downstream = "DEP_DOWN_" + suffix;
    insertJob(upstreamOne);
    insertJob(upstreamTwo);

    String created = postJob(downstream, upstreamOne);
    assertThat(created).contains("\"code\":\"SUCCESS\"");
    Long id = owner.queryForObject(
        "SELECT id FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?",
        Long.class,
        TENANT,
        downstream);
    assertThat(dependencies(downstream)).isEqualTo(upstreamOne);

    client
        .put()
        .uri("/api/console/job-definitions/" + id)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-put-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"tenantId\":\"" + TENANT + "\",\"dependsOnJobCode\":\"" + upstreamTwo + "\"}")
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(dependencies(downstream)).isEqualTo(upstreamTwo);

    client
        .get()
        .uri("/api/console/job-definitions/" + id + "?tenantId=" + TENANT)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> assertThat(body).contains("\"dependsOnJobCodes\":[\"" + upstreamTwo + "\"]"));

    client
        .get()
        .uri("/api/console/queries/job-definitions?tenantId=" + TENANT
            + "&includeDisabled=true&dependsOnJobCode=" + upstreamTwo)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("\"jobCode\":\"" + downstream + "\""));
  }

  @Test
  @DisplayName("复制作业保留依赖；形成环路的依赖在普通角色下仍被拒绝")
  void shouldCopyDependenciesAndRejectCycles_whenApplicationRoleCannotBypassRls() {
    String suffix = suffix();
    String upstream = "CYC_UP_" + suffix;
    String downstream = "CYC_DOWN_" + suffix;
    insertJob(upstream);
    assertThat(postJob(downstream, upstream)).contains("\"code\":\"SUCCESS\"");
    Long downstreamId = owner.queryForObject(
        "SELECT id FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?",
        Long.class,
        TENANT,
        downstream);
    Long upstreamId = owner.queryForObject(
        "SELECT id FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?",
        Long.class,
        TENANT,
        upstream);

    String copy = "CYC_COPY_" + suffix;
    client
        .post()
        .uri("/api/console/job-definitions/" + downstreamId + "/copy?tenantId=" + TENANT
            + "&newJobCode=" + copy)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-copy-" + suffix)
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(dependencies(copy)).isEqualTo(upstream);

    // upstream 依赖 downstream 会形成 upstream → downstream → upstream 环路；只有读到已有依赖边才能拒绝。
    client
        .put()
        .uri("/api/console/job-definitions/" + upstreamId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cycle-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"tenantId\":\"" + TENANT + "\",\"dependsOnJobCode\":\"" + downstream + "\"}")
        .exchange()
        .expectStatus()
        .is4xxClientError();
    assertThat(dependencies(upstream)).isEmpty();
  }

  private String postJob(String jobCode, String dependsOnJobCode) {
    return client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-create-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "tenantId": "%s",
              "jobCode": "%s",
              "jobName": "dependency tenant context",
              "jobType": "GENERAL",
              "scheduleType": "MANUAL",
              "dependsOnJobCode": "%s"
            }
            """.formatted(TENANT, jobCode, dependsOnJobCode))
        .exchange()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private void insertJob(String jobCode) {
    owner.update("""
        INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone)
        VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai')
        """, TENANT, jobCode, jobCode);
  }

  private String dependencies(String downstream) {
    return String.join(
        ",",
        owner.queryForList(
            "SELECT upstream_job_code FROM batch.job_dependency"
                + " WHERE tenant_id = ? AND downstream_job_code = ? ORDER BY dependency_order",
            String.class,
            TENANT,
            downstream));
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }

  /** 每次借出连接时按开关切换到普通角色或复位，连接归还连接池后不会把角色带给其他用例。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class ApplicationRoleConnections {

    @Bean
    static BeanPostProcessor applicationRoleDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if ("dataSource".equals(beanName) && bean instanceof DataSource dataSource) {
            return new RoleSwitchingDataSource(dataSource);
          }
          return bean;
        }
      };
    }
  }

  static final class RoleSwitchingDataSource extends DelegatingDataSource implements AutoCloseable {

    RoleSwitchingDataSource(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection connection = obtainTargetDataSource().getConnection();
      try (Statement statement = connection.createStatement()) {
        statement.execute(restricted ? "SET ROLE " + APPLICATION_ROLE : "RESET ROLE");
      }
      return connection;
    }

    @Override
    public void close() throws Exception {
      if (obtainTargetDataSource() instanceof AutoCloseable closeable) {
        closeable.close();
      }
    }
  }
}
