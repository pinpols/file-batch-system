package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("业务库运行账号必须受 RLS 约束")
class BusinessDataSourceRoleCheckIntegrationTest {

  private static PostgreSQLContainer postgres;
  private static HikariDataSource adminDataSource;
  private static HikariDataSource writerDataSource;

  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgresContainers.business();
    postgres.start();
    adminDataSource = dataSource(postgres.getUsername(), postgres.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(adminDataSource);
    jdbc.execute("CREATE ROLE role_guard_writer LOGIN PASSWORD 'role_guard_password' "
        + "NOSUPERUSER NOBYPASSRLS");
    writerDataSource = dataSource("role_guard_writer", "role_guard_password");
  }

  @AfterAll
  static void stopPostgres() {
    if (writerDataSource != null) {
      writerDataSource.close();
    }
    if (adminDataSource != null) {
      adminDataSource.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  @DisplayName("SUPERUSER/BYPASSRLS 账号使健康检查 DOWN 且阻止启动")
  void unsafeRoleFailsHealthAndStartup() {
    assertThat(
            new BusinessDataSourceRoleHealthIndicator(adminDataSource).health().getStatus())
        .isEqualTo(Status.DOWN);
    assertThatThrownBy(
            () -> new BusinessDataSourceRoleStartupCheck(adminDataSource).checkOnStartup())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bypasses PostgreSQL RLS");
  }

  @Test
  @DisplayName("NOSUPERUSER/NOBYPASSRLS writer 账号通过健康检查与启动守门")
  void writerRolePassesHealthAndStartup() {
    assertThat(
            new BusinessDataSourceRoleHealthIndicator(writerDataSource).health().getStatus())
        .isEqualTo(Status.UP);
    assertThatCode(() -> new BusinessDataSourceRoleStartupCheck(writerDataSource).checkOnStartup())
        .doesNotThrowAnyException();
  }

  private static HikariDataSource dataSource(String username, String password) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(2);
    return new HikariDataSource(config);
  }
}
